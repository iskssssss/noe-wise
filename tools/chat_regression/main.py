"""显式命令区分离线生成、接口准备和收费模型执行。"""
import argparse
from collections import Counter
from datetime import datetime
import hashlib
import json
import os
from pathlib import Path
import re
import uuid
from contextlib import contextmanager

from cases import generate
from client import Api, login
from runner import Budget, execute, read, save
from report import build


@contextmanager
def batch_lock(root):
    path = root / "execution.lock"
    try:
        fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL)
    except FileExistsError:
        raise RuntimeError("批次已被占用；若上次进程已退出，核实后移除execution.lock再续跑") from None
    try:
        os.write(fd, str(os.getpid()).encode()); os.close(fd)
        yield
    finally:
        path.unlink(missing_ok=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["plan", "prepare", "run", "report"])
    parser.add_argument("--root", type=Path, required=True, help="独立批次输出目录")
    parser.add_argument("--seed", type=int, default=20260923)
    parser.add_argument("--batch", default=None)
    parser.add_argument("--api", default="http://localhost:3000/api/v1")
    parser.add_argument("--platform", default="http://localhost:8100/api/v1")
    parser.add_argument("--fixture", default="http://127.0.0.1:18765")
    parser.add_argument("--model-id", default=None)
    parser.add_argument("--limit", type=int, default=20, help="累计选取会话数，依次20/100/300/1000")
    parser.add_argument("--case-id", action="append", help="定向验证指定用例，可重复；覆盖前缀 limit 选择，不修改冻结计划")
    parser.add_argument("--concurrency", type=int, default=2)
    parser.add_argument("--turn-timeout", type=int, default=540)
    parser.add_argument("--max-seconds", type=int, default=7200)
    parser.add_argument("--max-turns", type=int, default=200)
    parser.add_argument("--max-tokens", type=int, default=2000000)
    parser.add_argument("--failure-limit", type=int, default=5)
    args = parser.parse_args()
    if args.command == "plan":
        if (args.root / "plan.json").exists(): parser.error("批次已存在；禁止覆盖已冻结计划")
        batch = args.batch or "chatreg-" + datetime.now().strftime("%Y%m%d-%H%M%S") + "-" + uuid.uuid4().hex[:6]
        if not re.fullmatch(r"[a-z0-9][a-z0-9-]{0,47}", batch): parser.error("batch仅允许小写字母数字连字符，最多48字符")
        plan = generate(args.seed)
        plan = json.loads(json.dumps(plan,ensure_ascii=False).replace("{batch}",batch))
        plan["batch"] = batch
        plan["fingerprint"] = hashlib.sha256(json.dumps(plan,sort_keys=True).encode()).hexdigest()
        save(args.root / "plan.json", plan)
        print(json.dumps({"batch":batch,"sessions":len(plan["cases"]),"turns":sum(len(c["turns"]) for c in plan["cases"]),"categories":Counter(c["category"] for c in plan["cases"])},ensure_ascii=False,indent=2))
        return
    if args.command == "report":
        print(json.dumps(build(args.root),ensure_ascii=False,indent=2)); return
    if args.command == "run" and (args.root / "stop.json").exists():
        parser.error("批次已因模型错误或额度信号停止，请先核实stop.json；不自动恢复")
    plan = read(args.root / "plan.json")
    original = dict(plan); fingerprint = original.pop("fingerprint")
    if hashlib.sha256(json.dumps(original,sort_keys=True).encode()).hexdigest() != fingerprint:
        parser.error("计划已修改；新建批次以保留原验证证据")
    endpoint_path = args.root / "endpoints.json"
    endpoints = {"api":args.api, "platform":args.platform, "fixture":args.fixture}
    if endpoint_path.exists() and read(endpoint_path) != endpoints: parser.error("批次端点已冻结，不能切换环境")
    save(endpoint_path, endpoints)
    api = login(args.api)
    fixture = Api(args.fixture, os.environ.get("MCP_FIXTURE_TOKEN"))
    if fixture.request("GET", "/health").get("batch") != plan["batch"]: parser.error("MCP夹具batch与计划不匹配")
    if args.command == "prepare":
        if not args.model_id: parser.error("prepare需要 --model-id，显式选定已配置模型")
        from provision import provision
        with batch_lock(args.root):
            resources = provision(api, login(args.platform, True), args.root, plan["profiles"], plan["batch"], args.model_id, args.fixture + "/mcp")
        print("资源准备完成，用户数",len(resources["users"]),"智能体数",len(resources["agents"])); return
    if not 1 <= args.limit <= 1000 or not 1 <= args.concurrency <= 50:
        parser.error("limit应为1..1000；concurrency应为1..50")
    if min(args.max_turns,args.max_seconds,args.max_tokens,args.turn_timeout,args.failure_limit) <= 0:
        parser.error("执行预算必须为正")
    resources = read(args.root / "resources.json")
    if resources.get("batch") != plan["batch"] or len(resources["users"]) != 50 or len(resources["agents"]) != 10:
        parser.error("资源尚未完整准备或批次不匹配")
    if resources.get("pending_operations") or any(not agent.get("published") for agent in resources["agents"].values()):
        parser.error("资源准备存在未知结果或智能体未发布，不能启动聊天")
    budget = Budget(args.max_seconds,args.max_turns,args.max_tokens,args.failure_limit)
    execution_plan = plan
    if args.case_id:
        selected = set(args.case_id)
        known = {case["id"] for case in plan["cases"]}
        if selected - known: parser.error("指定用例不存在：" + ", ".join(sorted(selected - known)))
        execution_plan = {**plan, "cases": [case for case in plan["cases"] if case["id"] in selected]}
        args.limit = len(execution_plan["cases"])
    try:
        with batch_lock(args.root):
            execute(api,fixture,execution_plan,resources,args.root,args.limit,args.concurrency,args.turn_timeout,budget)
    finally:
        summary = build(args.root)
        print(json.dumps(summary,ensure_ascii=False,indent=2))
    if any(summary["counts"].get(status,0) for status in ["failed","interrupted","blocked"]): raise SystemExit(1)


if __name__ == "__main__":
    main()
