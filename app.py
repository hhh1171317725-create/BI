"""大航海日报本地分析系统。"""

from __future__ import annotations

import csv
import io
import json
import re
import threading
import webbrowser
from collections import defaultdict
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.request import Request, urlopen


APP_DIR = Path(__file__).resolve().parent
SETTINGS_PATH = Path.home() / ".dahanghai_analysis_system" / "settings.json"
HOST = "127.0.0.1"
PORT = 8765
EXPORT_URL = "https://report.rockorca.com/api/dcMarketingDhhDaily/getDcMarketingDhhDailyExport"
NUMERIC_FIELDS = ("消耗", "现金消耗", "赠款消耗", "预估佣金", "结算数", "转化数", "注册数")


def number(value: object) -> float:
    try:
        return float(str(value or "0").replace(",", ""))
    except (TypeError, ValueError):
        return 0.0


def project_from_task(task: str) -> str:
    task = task or "未填写"
    if "闲鱼" in task:
        return "淘宝闲鱼促活"
    if "MCVR" in task:
        return "淘宝闪购MCVR"
    if "CVR" in task:
        return "淘宝促购CVR"
    if "UV" in task:
        return "淘宝促活UV"
    return "其他项目"


def fetch_rows(token: str, user_id: str) -> list[dict]:
    if not token.strip():
        raise ValueError("请粘贴当前 x-token")
    headers = {
        "Accept": "application/json, text/plain, */*",
        "Referer": "https://report.rockorca.com/",
        "X-Token": token.strip(),
        "X-User-Id": user_id.strip() or "20",
        "Cookie": f"x-token={token.strip()}",
    }
    request = Request(EXPORT_URL, headers=headers, method="GET")
    with urlopen(request, timeout=90) as response:
        content = response.read()
    for encoding in ("utf-8-sig", "gb18030", "utf-8"):
        try:
            text = content.decode(encoding)
            break
        except UnicodeDecodeError:
            continue
    else:
        raise ValueError("无法识别导出文件的编码")

    rows = []
    for raw in csv.DictReader(io.StringIO(text)):
        date = (raw.get("日期") or "").strip()
        if not date or date == "-":
            continue
        task = (raw.get("任务名") or "").strip() or "未填写"
        row = {
            "日期": date[:10],
            "媒体": (raw.get("媒体") or "").strip() or "未填写",
            "优化师": (raw.get("优化师") or "").strip() or "未填写",
            "任务名": task,
            "项目": project_from_task(task),
        }
        row.update({field: number(raw.get(field)) for field in NUMERIC_FIELDS})
        rows.append(row)
    return rows


def load_settings() -> dict:
    try:
        return json.loads(SETTINGS_PATH.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return {"token": "", "userId": "20"}


def save_settings(token: str, user_id: str) -> None:
    SETTINGS_PATH.parent.mkdir(parents=True, exist_ok=True)
    SETTINGS_PATH.write_text(json.dumps({"token": token.strip(), "userId": user_id.strip() or "20"}), encoding="utf-8")


def aggregate(rows: list[dict], fields: str | list[str]) -> list[dict]:
    fields = [fields] if isinstance(fields, str) else fields
    buckets: dict[tuple[str, ...], dict] = defaultdict(lambda: {metric: 0.0 for metric in NUMERIC_FIELDS})
    for row in rows:
        key = tuple(row[field] for field in fields)
        bucket = buckets[key]
        for metric in NUMERIC_FIELDS:
            bucket[metric] += row[metric]

    result = []
    for key, values in buckets.items():
        spend = values["消耗"]
        cash = values["现金消耗"]
        commission = values["预估佣金"]
        result.append({
            **dict(zip(fields, key)),
            **{metric: round(value, 2) for metric, value in values.items()},
            "现金利润": round(commission - cash, 2),
            "ROI": round(commission / spend, 4) if spend else 0,
            "现金ROI": round(commission / cash, 4) if cash else 0,
            "结算单价": round(commission / values["结算数"], 2) if values["结算数"] else 0,
            "转化成本": round(spend / values["转化数"], 2) if values["转化数"] else 0,
            "注册成本": round(spend / values["注册数"], 2) if values["注册数"] else 0,
        })
    return sorted(result, key=lambda item: item["消耗"], reverse=True)


def build_analysis(rows: list[dict], start: str = "", end: str = "") -> dict:
    filtered = [row for row in rows if (not start or row["日期"] >= start) and (not end or row["日期"] <= end)]
    total = aggregate(filtered, "项目")
    sums = {metric: round(sum(item[metric] for item in total), 2) for metric in NUMERIC_FIELDS}
    cash_profit = round(sums["预估佣金"] - sums["现金消耗"], 2)
    return {
        "rows": len(filtered),
        "range": [min((row["日期"] for row in filtered), default="-"), max((row["日期"] for row in filtered), default="-")],
        "summary": {
            **sums,
            "现金利润": cash_profit,
            "ROI": round(sums["预估佣金"] / sums["消耗"], 4) if sums["消耗"] else 0,
            "现金ROI": round(sums["预估佣金"] / sums["现金消耗"], 4) if sums["现金消耗"] else 0,
        },
        "by_optimizer": aggregate(filtered, "优化师"),
        "by_project": total,
        "by_date": sorted(aggregate(filtered, "日期"), key=lambda item: item["日期"], reverse=True),
        "by_task": aggregate(filtered, "任务名"),
        "by_optimizer_date": aggregate(filtered, ["日期", "优化师"]),
        "by_project_date": aggregate(filtered, ["日期", "项目"]),
        "by_task_date": aggregate(filtered, ["日期", "任务名"]),
    }


class Handler(BaseHTTPRequestHandler):
    rows: list[dict] = []

    def log_message(self, _format: str, *_args: object) -> None:
        return

    def send_json(self, payload: dict, status: int = 200) -> None:
        content = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(content)))
        self.end_headers()
        self.wfile.write(content)

    def do_GET(self) -> None:
        if self.path == "/api/settings":
            self.send_json(load_settings())
            return
        if self.path == "/":
            content = (APP_DIR / "index.html").read_bytes()
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(content)))
            self.end_headers()
            self.wfile.write(content)
            return
        if self.path == "/echarts.min.js":
            content = (APP_DIR / "echarts.min.js").read_bytes()
            self.send_response(200)
            self.send_header("Content-Type", "application/javascript; charset=utf-8")
            self.send_header("Content-Length", str(len(content)))
            self.end_headers()
            self.wfile.write(content)
            return
        self.send_error(404)

    def do_POST(self) -> None:
        try:
            length = int(self.headers.get("Content-Length", "0"))
            payload = json.loads(self.rfile.read(length).decode("utf-8"))
            if self.path == "/api/load":
                if payload.get("remember", True):
                    save_settings(payload.get("token", ""), payload.get("userId", "20"))
                Handler.rows = fetch_rows(payload.get("token", ""), payload.get("userId", "20"))
                self.send_json(build_analysis(Handler.rows))
            elif self.path == "/api/analyze":
                if not Handler.rows:
                    raise ValueError("请先加载数据")
                self.send_json(build_analysis(Handler.rows, payload.get("start", ""), payload.get("end", "")))
            else:
                self.send_error(404)
        except Exception as exc:  # Surface API errors to the local UI.
            self.send_json({"error": str(exc)}, 400)


def main() -> None:
    server = ThreadingHTTPServer((HOST, PORT), Handler)
    url = f"http://{HOST}:{PORT}"
    print(f"大航海分析系统已启动：{url}")
    threading.Timer(0.6, lambda: webbrowser.open(url)).start()
    server.serve_forever()


if __name__ == "__main__":
    main()
