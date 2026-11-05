from pathlib import Path
from typing import Iterable, Any
import joblib
import numpy as np
import pandas as pd
from collections import deque
from threading import RLock


class WifiLocatorV4:
    """
    v4.3 test:
    1) v3 floor KNN remains unchanged.
    2) Anchor comparison uses only APs actually observed in the current Android scan.
    3) For each anchor, calculate median absolute RSSI error (MAE) across its training fingerprints.
    4) Keep the existing 2-scan Anchor anti-jitter logic.
    """

    FLOOR_TO_ID = {
        0: "k-area-airport-1f",
        1: "k-area-airport-mrt",
    }

    def __init__(self, base_dir=None):
        self.base_dir = Path(base_dir) if base_dir else Path(__file__).resolve().parent

        self.floor_model = joblib.load(
            self.base_dir / "wifi_floor_knn_v3_final.pkl"
        )
        self.anchor_coords = joblib.load(
            self.base_dir / "anchor_coords_v4.pkl"
        )
        self.features = list(joblib.load(
            self.base_dir / "train_bssid_list_v3_final.pkl"
        ))

        training_file = self.base_dir / "Final_Training_Data_v4_2_fixed_anchors.csv"
        if not training_file.exists():
            raise FileNotFoundError(
                f"v4.3 needs {training_file.name} in the project root."
            )

        self.anchor_training = pd.read_csv(training_file)
        self.feature_lookup = {
            str(x).lower().strip(): x
            for x in self.features
        }

        self.anchor_X = {}
        self.anchor_y = {}
        for floor in sorted(self.anchor_training["z"].astype(int).unique()):
            part = self.anchor_training[
                self.anchor_training["z"].astype(int) == int(floor)
            ].reset_index(drop=True)
            self.anchor_X[int(floor)] = part[self.features].astype(float).to_numpy()
            self.anchor_y[int(floor)] = part["point_id"].astype(str).to_numpy()






        # Keep anchor stabilization histories independent between client sessions.
        self._session_states = {}
        self._session_lock = RLock()

    def _signal_pair(self, item: Any):
        if isinstance(item, dict):
            bssid = item.get("bssid")
            level = item.get("level")
        else:
            bssid = getattr(item, "bssid", None)
            level = getattr(item, "level", None)

        if bssid is None or level is None:
            raise ValueError("每筆 signal 必須包含 bssid 與 level")

        return str(bssid).lower().strip(), float(level)

    def build_feature_row(self, signals: Iterable[Any]):
        buckets = {}
        total = 0

        for item in signals:
            bssid, level = self._signal_pair(item)
            total += 1
            buckets.setdefault(bssid, []).append(level)

        if total == 0:
            raise ValueError("signals 不可為空")

        incoming = {
            b: float(np.mean(v))
            for b, v in buckets.items()
        }

        row = {f: -100.0 for f in self.features}
        matched_features = []

        for bssid, level in incoming.items():
            feature = self.feature_lookup.get(bssid)
            if feature is not None:
                row[feature] = level
                matched_features.append(feature)

        X = pd.DataFrame([row], columns=self.features)

        diagnostics = {
            "receivedSignalCount": total,
            "uniqueSignalCount": len(incoming),
            "matchedFeatureCount": len(matched_features),
            "modelFeatureCount": len(self.features),
            "matchedRatio": len(matched_features) / max(1, len(incoming)),
        }

        return X, diagnostics, matched_features

    def _predict_anchor_observed_mae(self, X, floor, matched_features):
        train_X = self.anchor_X[int(floor)]
        train_y = self.anchor_y[int(floor)]

        idx_lookup = {f: i for i, f in enumerate(self.features)}
        observed_idx = np.array(
            [idx_lookup[f] for f in matched_features if f in idx_lookup],
            dtype=int,
        )

        if len(observed_idx) < 5:
            raise ValueError("可用 Anchor 比對 AP 少於 5 個")

        live = X[self.features].astype(float).to_numpy()[0]



        row_mae = np.mean(
            np.abs(train_X[:, observed_idx] - live[observed_idx]),
            axis=1,
        )

        anchor_scores = {}
        for anchor in np.unique(train_y):

            anchor_scores[str(anchor)] = float(
                np.median(row_mae[train_y == anchor])
            )

        ranked = sorted(anchor_scores.items(), key=lambda kv: kv[1])
        best_anchor, best_score = ranked[0]
        second_anchor, second_score = ranked[1] if len(ranked) > 1 else (None, None)

        return best_anchor, best_score, second_anchor, second_score

    def predict(self, signals, session_id=None):
        X, diagnostics, matched_features = self.build_feature_row(signals)

        if diagnostics["matchedFeatureCount"] < 5:
            return {
                "mode": "real",
                "modelVersion": "v4.4-observed-mae-majority3",
                "validLocation": False,
                "floor": None,
                "floorId": None,
                "anchorId": None,
                "gridX": None,
                "gridY": None,
                "x": None,
                "y": None,
                "floorConfidence": None,
                **diagnostics,
                "warnings": ["有效匹配 AP 少於 5 個，拒絕定位"],
            }


        floor = int(self.floor_model.predict(X)[0])

        confidence = None
        if hasattr(self.floor_model, "predict_proba"):
            probs = self.floor_model.predict_proba(X)[0]
            classes = list(self.floor_model.classes_)
            if floor in classes:
                confidence = float(probs[classes.index(floor)])

        raw_anchor_id, best_score, second_anchor, second_score = (
            self._predict_anchor_observed_mae(X, floor, matched_features)
        )

        # A shared model serves all clients, so its history must be session-scoped.
        session_key = str(session_id) if session_id else "legacy"
        with self._session_lock:
            if session_key not in self._session_states:
                # Bound in-memory session state to avoid unlimited growth.
                if len(self._session_states) >= 512:
                    oldest_key = next(iter(self._session_states))
                    del self._session_states[oldest_key]
                self._session_states[session_key] = {
                    "last_floor": None,
                    "stable_anchor": {},
                    "raw_history": {},
                }

            state = self._session_states[session_key]
            stable_anchor = state["stable_anchor"]
            raw_history = state["raw_history"]

            if state["last_floor"] != floor:
                state["last_floor"] = floor
                stable_anchor[floor] = raw_anchor_id
                raw_history[floor] = deque([raw_anchor_id], maxlen=3)
            else:
                if floor not in raw_history:
                    raw_history[floor] = deque(maxlen=3)

                history = raw_history[floor]
                history.append(raw_anchor_id)

                stable = stable_anchor.get(floor)
                if stable is None:
                    stable_anchor[floor] = raw_anchor_id
                else:
                    counts = {}
                    for a in history:
                        counts[a] = counts.get(a, 0) + 1

                    majority_anchor = max(
                        counts,
                        key=lambda a: (counts[a], 1 if a == raw_anchor_id else 0)
                    )
                    if majority_anchor != stable and counts[majority_anchor] >= 2:
                        stable_anchor[floor] = majority_anchor

            anchor_id = stable_anchor[floor]
        z, pred_y, pred_x = self.anchor_coords[anchor_id]

        pixel_anchor_2f = {
            "END_MRT": (100.0, 120.0),
            "ESCALATOR_END": (190.0, 600.0),
            "MRT_P25": (167.5, 480.0),
            "MRT_P50": (145.0, 360.0),
            "MRT_P75": (122.5, 240.0),
        }

        if floor == 1 and anchor_id in pixel_anchor_2f:
            pixel_x, pixel_y = pixel_anchor_2f[anchor_id]
        else:
            pixel_x = float(pred_x) * 45 + 100
            pixel_y = float(pred_y) * 80 + 100

        warnings = []
        if raw_anchor_id != anchor_id:
            warnings.append(
                f"Anchor 防抖中：raw={raw_anchor_id}，3次視窗暫時保留={anchor_id}"
            )
        if second_score is not None and (second_score - best_score) < 1.0:
            warnings.append(
                f"Anchor 相近：{raw_anchor_id} 與 {second_anchor} 分數差小於 1 dB"
            )
        if floor == 1 and anchor_id not in pixel_anchor_2f:
            warnings.append("此 2F Anchor 尚未完成地圖像素校正")
        if diagnostics["matchedFeatureCount"] < 20:
            warnings.append("有效匹配 AP 偏少")

        return {
            "mode": "real",
            "modelVersion": "v4.4-observed-mae-majority3",
            "floor": floor,
            "floorId": self.FLOOR_TO_ID[floor],
            "anchorId": anchor_id,
            "rawAnchorId": raw_anchor_id,
            "anchorStabilized": raw_anchor_id != anchor_id,
            "anchorScoreMae": float(best_score),
            "secondAnchorId": second_anchor,
            "secondAnchorScoreMae": (
                None if second_score is None else float(second_score)
            ),
            "anchorMarginMae": (
                None if second_score is None else float(second_score - best_score)
            ),
            "gridX": float(pred_x),
            "gridY": float(pred_y),
            "x": pixel_x,
            "y": pixel_y,
            "floorConfidence": confidence,
            **diagnostics,
            "warnings": warnings,
        }
