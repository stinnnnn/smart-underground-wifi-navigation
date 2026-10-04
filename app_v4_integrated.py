from pathlib import Path
import time
import json
from datetime import datetime
from collections import deque
from typing import List, Optional
import pandas as pd
from fastapi import FastAPI, HTTPException, Request
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel

from wifi_locator_v3 import WifiLocatorV3
from wifi_locator_v4 import WifiLocatorV4
from route_safe_engine import plan_safe_route, DQN_ENGINE, DQN_ERROR
from places_to_dqn import PLACE_DQN_MAP, place_to_dqn
from anchor_to_dqn import anchor_to_dqn


BASE_DIR = Path(__file__).resolve().parent

app = FastAPI(title="AR Indoor Navigation API v4")

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)




locator = None
try:
    locator = WifiLocatorV3(BASE_DIR)
    print(f" Wi-Fi v3 定位模型載入成功：{len(locator.features)} 個 BSSID 特徵")
except Exception as e:
    print(f" Wi-Fi v3 定位模型載入失敗：{e}")





real_locator_v4 = None
try:
    real_locator_v4 = WifiLocatorV4(BASE_DIR)
    print(
        f" Wi-Fi v4 Anchor 定位模型載入成功："
        f"{len(real_locator_v4.features)} 個 BSSID 特徵"
    )
except Exception as e:
    print(f" Wi-Fi v4 Anchor 定位模型載入失敗：{e}")




print(
    " DQN 安全路由引擎已載入"
    if DQN_ENGINE is not None
    else f" DQN 尚未啟用，先使用最短安全 fallback：{DQN_ERROR}"
)








REAL_DEDUPE_SECONDS = 8.0
_last_real_by_client = {}
_last_current_dqn = None




class WifiSignal(BaseModel):
    bssid: str
    level: int


class RealWifiRequest(BaseModel):
    signals: List[WifiSignal]


class DemoLocateRequest(BaseModel):
    target_floor: str = "1f"


class ReplayRequest(BaseModel):
    sample_index: int





@app.post("/api/locate")
@app.post("/api/locate-real")
def locate_real(req: RealWifiRequest, request: Request):
    if real_locator_v4 is None:
        raise HTTPException(
            status_code=503,
            detail="Wi-Fi v4 Anchor 定位模型尚未載入完成"
        )

    try:
        client_key = (
            request.client.host
            if request.client is not None
            else "unknown"
        )
        now = time.monotonic()

        previous = _last_real_by_client.get(client_key)
        if previous is not None:
            elapsed = now - previous["time"]
            if elapsed < REAL_DEDUPE_SECONDS:
                cached = dict(previous["result"])
                cached["deduplicated"] = True
                cached["dedupeAgeSeconds"] = round(elapsed, 3)

                print(
                    " [REAL-v4] 略過過密請求 "
                    f"client={client_key} "
                    f"age={elapsed:.2f}s "
                    f"receivedAP={len(req.signals)}"
                )
                return cached

        result = real_locator_v4.predict(req.signals)



        global _last_current_dqn
        if result.get("validLocation") is not False and result.get("anchorId"):
            try:
                dqn_pos = anchor_to_dqn(result["anchorId"])
            except Exception:
                dqn_pos = None

            if dqn_pos is not None:
                result["dqnX"] = int(dqn_pos["x"])
                result["dqnY"] = int(dqn_pos["y"])
                result["dqnFloorId"] = "k-floor-grid"
                _last_current_dqn = {
                    "status": "success",
                    "x": int(dqn_pos["x"]),
                    "y": int(dqn_pos["y"]),
                    "anchorId": result.get("anchorId"),
                    "matchedFeatureCount": result.get("matchedFeatureCount"),
                    "floor": result.get("floor"),
                    "floorId": result.get("floorId"),
                }


        _last_real_by_client[client_key] = {
            "time": now,
            "result": dict(result),
        }

        if result.get("validLocation") is False:
            print(
                " [REAL-v4] 定位拒絕 "
                f"matchedAP={result['matchedFeatureCount']} "
                f"receivedAP={result['receivedSignalCount']}"
            )
            return result

        print(
            " [REAL-v4] "
            f"floor={result['floor']} "
            f"anchor={result.get('anchorId')} "
            f"grid=({result['gridX']:.2f},{result['gridY']:.2f}) "
            f"matchedAP={result['matchedFeatureCount']}"
        )
        return result
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"v4 定位失敗：{e}")





@app.post("/api/locate-demo")
def locate_demo(req: DemoLocateRequest):
    if req.target_floor.lower() == "2f":
        floor = 1
        pred_y = 3.0
        pred_x = 6.4
        floor_id = "k-area-airport-mrt"
    else:
        floor = 0
        pred_y = 4.0
        pred_x = 10.0
        floor_id = "k-area-airport-1f"

    return {
        "mode": "demo",
        "floor": floor,
        "floorId": floor_id,
        "gridX": pred_x,
        "gridY": pred_y,
        "x": pred_x * 45 + 100,
        "y": pred_y * 80 + 100,
        "floorConfidence": None,
        "matchedFeatureCount": 0,
        "warnings": ["此為 Demo 模式，座標由展示控制器指定，不代表 KNN 推論結果"],
    }






@app.post("/api/locate-replay")
def locate_replay(req: ReplayRequest):
    if locator is None:
        raise HTTPException(status_code=503, detail="Wi-Fi v3 定位模型尚未載入完成")

    path = BASE_DIR / "Final_Training_Data_v3_final.csv"
    if not path.exists():
        raise HTTPException(status_code=503, detail="找不到 Final_Training_Data_v3_final.csv")

    df = pd.read_csv(path)
    if req.sample_index < 0 or req.sample_index >= len(df):
        raise HTTPException(
            status_code=400,
            detail=f"sample_index 必須介於 0 到 {len(df)-1}",
        )

    row = df.iloc[req.sample_index]
    signals = [
        {"bssid": bssid, "level": int(round(float(row[bssid])))}
        for bssid in locator.features
        if float(row[bssid]) != -100
    ]

    result = locator.predict(signals)
    result["mode"] = "replay"
    result["sampleIndex"] = req.sample_index
    result["groundTruth"] = {
        "floor": int(row["z"]),
        "gridY": float(row["y"]),
        "gridX": float(row["x"]),
    }
    result["trainingSample"] = True
    result["warnings"] = list(result.get("warnings", [])) + [
        "此 Replay 使用訓練集中真實 fingerprint，只用來驗證串接；不可當獨立模型準確率"
    ]
    return result


@app.get("/api/model-status")
def model_status():
    replay_path = BASE_DIR / "Final_Training_Data_v3_final.csv"
    replay_sample_count = 0

    try:
        if replay_path.exists():
            replay_sample_count = len(
                pd.read_csv(replay_path, usecols=["z"])
            )
    except Exception as e:
        print(f" Replay 筆數讀取失敗：{e}")

    return {

        "wifiV3Loaded": locator is not None,
        "wifiV4Loaded": real_locator_v4 is not None,
        "realModelVersion": "v4-anchor" if real_locator_v4 else None,
        "wifiFeatureCount": (
            len(real_locator_v4.features)
            if real_locator_v4
            else (len(locator.features) if locator else 0)
        ),
        "replaySampleCount": replay_sample_count,
        "dqnModuleAvailable": DQN_ENGINE is not None,
        "dqnWeightsLoaded": DQN_ENGINE is not None,
        "dqnError": DQN_ERROR,
    }





@app.get("/api/maps")
def get_maps():
    return [{"id": "k-area-airport", "name": "台北車站 K 區與機捷連通道"}]


@app.get("/api/floors")
def get_floors(mapId: str = ""):
    return [
        {
            "id": "k-area-airport-1f",
            "floorName": "B1 K區地下街",
            "imageUrl": "k_area_to_airport_1f.jpg",
            "imageLeft": 0,
            "imageTop": 0,
            "imageWidth": 2000,
        },
        {
            "id": "k-area-airport-mrt",
            "floorName": "R層 機捷連通道",
            "imageUrl": "k_area_to_airport_2f.jpg",
            "imageLeft": 0,
            "imageTop": 0,
            "imageWidth": 2000,
        },
    ]


@app.get("/api/places")
def get_places(mapId: str = ""):
    return [
        {"id": "start_k_area", "name": "K區起點 (我在這裡！)", "mapId": "k-area-airport", "floorId": "k-area-airport-1f", "category": "起點", "x": 250, "y": 570},
        {"id": "end_mrt_target", "name": "機捷連通道 (測試終點)", "mapId": "k-area-airport", "floorId": "k-area-airport-mrt", "category": "目的地", "x": 100, "y": 120},
        {"id": "k12_escalator", "name": "K12 手扶梯", "mapId": "k-area-airport", "floorId": "k-area-airport-1f", "category": "設施", "x": 169, "y": 340},
        {"id": "daiso", "name": "大創 DAISO", "mapId": "k-area-airport", "floorId": "k-area-airport-1f", "category": "商店", "x": 517, "y": 496},
        {"id": "hobby_off", "name": "Hobby OFF", "mapId": "k-area-airport", "floorId": "k-area-airport-1f", "category": "商店", "x": 512, "y": 432},
        {"id": "central_square", "name": "中央廣場", "mapId": "k-area-airport", "floorId": "k-area-airport-1f", "category": "地標", "x": 1040, "y": 389},
        {"id": "711_store", "name": "7-ELEVEN", "mapId": "k-area-airport", "floorId": "k-area-airport-1f", "category": "商店", "x": 1147, "y": 503},
        {"id": "marugame", "name": "丸龜製麵", "mapId": "k-area-airport", "floorId": "k-area-airport-1f", "category": "餐廳", "x": 1564, "y": 428},
    ]





class GridPathRequest(BaseModel):
    mapId: str = ""
    startFloorId: str = ""
    targetFloorId: str = ""
    startX: float = 0
    startY: float = 0
    destinationPlaceId: str = ""
    startAnchorId: Optional[str] = None
    startDqnX: Optional[float] = None
    startDqnY: Optional[float] = None


@app.post("/api/routes")
def get_shortest_path(req: GridPathRequest):
    if req.destinationPlaceId not in PLACE_DQN_MAP:
        raise HTTPException(
            status_code=404,
            detail=f"找不到目的地：{req.destinationPlaceId}"
        )

    if req.startAnchorId:
        try:
            anchor_pos = anchor_to_dqn(req.startAnchorId)
        except Exception as exc:
            raise HTTPException(status_code=400, detail=str(exc))

        if anchor_pos is None:
            raise HTTPException(
                status_code=400,
                detail="目前定位點不是已驗證的 K 區 B1 DQN Anchor"
            )
        start_xy = (int(anchor_pos["x"]), int(anchor_pos["y"]))

    elif req.startDqnX is not None and req.startDqnY is not None:
        start_xy = (
        int(round(req.startDqnX)),
        int(round(req.startDqnY)),
    )

    elif _last_current_dqn is not None:
        start_xy = (
            int(_last_current_dqn["x"]),
            int(_last_current_dqn["y"]),
        )

    else:
        raise HTTPException(
            status_code=400,
            detail="尚未取得 REAL 定位藍點，無法建立導航起點"
        )

    goal_xy = place_to_dqn(req.destinationPlaceId)
    result = plan_safe_route(start_xy, goal_xy)

    if result.get("status") != "success":
        raise HTTPException(
            status_code=422,
            detail=result.get("message", "找不到路線")
        )

    distance_m = float(result.get("distance_m", 0))
    estimated_minutes = max(1, round(distance_m / 72)) if distance_m else 1

    return {
        **result,
        "routePoints": result["path"],
        "distance": distance_m,
        "estimatedTime": estimated_minutes,
        "destinationPlaceId": req.destinationPlaceId,
        "destinationName": PLACE_DQN_MAP[req.destinationPlaceId]["name"],
        "message": (
            f"已規劃至 {PLACE_DQN_MAP[req.destinationPlaceId]['name']} "
            f"入口走道；{result['algorithm']}"
        ),
    }


class LegacyDqnPathRequest(BaseModel):
    start_y: int
    start_x: int
    target_y: int
    target_x: int


@app.post("/api/get_path")
def legacy_get_path(req: LegacyDqnPathRequest):
    result = plan_safe_route(
        (req.start_x, req.start_y),
        (req.target_x, req.target_y),
    )
    if result.get("status") != "success":
        raise HTTPException(
            status_code=422,
            detail=result.get("message", "找不到路線")
        )
    return {
        **result,
        "message": f"路線完成；{result['algorithm']}",
    }


@app.get("/api/current-location")
def current_location():
    if _last_current_dqn is None:
        return {"status": "waiting"}
    return dict(_last_current_dqn)


@app.get("/api/places-dqn")
def places_dqn():
    return [
        {
            "id": place_id,
            **item,
        }
        for place_id, item in PLACE_DQN_MAP.items()
    ]


REVIEWS_FILE = BASE_DIR / "reviews.json"
NAV_FEEDBACK_FILE = BASE_DIR / "navigation_feedback.json"


def _read_json_list(path: Path):
    if not path.exists():
        return []
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
        return data if isinstance(data, list) else []
    except Exception:
        return []


def _write_json_list(path: Path, items):
    path.write_text(
        json.dumps(items, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )


class ReviewRequest(BaseModel):
    placeId: str
    star: int
    text: str = ""
    timestamp: Optional[int] = None


@app.get("/api/reviews")
def get_reviews(placeId: str):
    return [
        item
        for item in _read_json_list(REVIEWS_FILE)
        if item.get("placeId") == placeId
    ]


@app.post("/api/reviews")
def add_review(req: ReviewRequest):
    if req.placeId not in PLACE_DQN_MAP:
        raise HTTPException(status_code=404, detail="找不到此店家")
    if not 1 <= int(req.star) <= 5:
        raise HTTPException(status_code=400, detail="star 必須介於 1 到 5")

    items = _read_json_list(REVIEWS_FILE)
    entry = {
        "placeId": req.placeId,
        "star": int(req.star),
        "text": str(req.text or "")[:1000],
        "timestamp": int(req.timestamp or round(datetime.now().timestamp() * 1000)),
    }
    items.append(entry)
    _write_json_list(REVIEWS_FILE, items)
    return {"ok": True, "review": entry}


@app.post("/api/navigation-feedback")
async def navigation_feedback(request: Request):
    payload = await request.json()
    items = _read_json_list(NAV_FEEDBACK_FILE)
    items.append(payload)
    _write_json_list(NAV_FEEDBACK_FILE, items)
    return {"ok": True}


@app.get("/api/wifi-scans/points")
def get_wifi_points(mapId: str = "", floorId: str = ""):
    if mapId == "map_visual_3" or floorId == "k-floor-grid":
        return {"points": []}

    try:
        df = pd.read_csv(BASE_DIR / "Final_Training_Data_v3_final.csv")
        target_z = 0 if floorId == "k-area-airport-1f" else 1
        df_floor = df[df["z"] == target_z]

        unique_coords = df_floor[["x", "y"]].drop_duplicates()

        points = []
        for _, row in unique_coords.iterrows():
            points.append(
                {
                    "pointId": f"P({row['x']:.1f},{row['y']:.1f})",
                    "x": float(row["x"]),
                    "y": float(row["y"]),
                    "mapId": "k-area-airport",
                    "floorId": floorId,
                    "scanCount": 1,
                }
            )
        return {"points": points}
    except Exception as e:
        print(" 讀取 v3 CSV 失敗:", e)
        return {"points": []}
