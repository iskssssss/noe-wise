"""固定种子生成会话矩阵；预期值来自夹具，不从模型回复反推。"""
import hashlib
import random

BASE = "你是测试环境中的业务助手。仅使用当前用户和当前助手的事实。不得猜测工具结果，不得将失败描述为成功。问候不调用工具。明确的一次性查询可以直接执行；持续目标应保存进展。不要输出其他用户的标记。"
SKILLS = {
    "collect": "用户请求收集或纠正资料时，逐项保存项目代号、预算及用户纠正，回复以【资料】开头，已提供信息不重复询问。用户切换到总结时遵循总结技能的输出格式。",
    "summarize": "用户请求总结时，总结已确认的项目资料，保留具体值，不猜测缺失事实，回复以【总结】开头。用户切换回收集或纠正资料时遵循资料收集技能的输出格式。",
    "query": "查询记录必须调用 query_record，以工具结果为准。回复以【查询】开头。",
}


def profiles():
    specs = [
        ("social", "友好简洁地聊天。", [], []),
        ("query", "查询尽量简短，缺少记录编号时澄清。query_record 返回上下文引用时，必须调用 read_context_reference 读取用户要求的原文片段。", [],
         ["query_record", "read_context_reference"]),
        ("json", "用户要求 JSON 时仅输出 JSON 对象，不加代码围栏。", [], []),
        ("single", "收集项目资料时使用资料收集技能。", ["collect"], []),
        ("multi", "按用户请求切换资料收集和总结技能。", ["collect", "summarize"], []),
        ("collector", "持续维护项目资料，用户纠正优先。", ["collect"], []),
        ("continuity", "跨会话续接同一项目，保留已确认项目代号。", ["collect"], []),
        ("clarify", "查询用查询技能；没有可靠事实时明确说不知道。", ["query"], ["query_record"]),
        ("operator", "可操作独立测试记录。只有用户明确要求写入时才调用 write_record，使用用户给出的 request_key。", [], ["query_record", "write_record", "fault_probe"]),
        ("artifact", "按请求用代码工具生成真实文件；使用 fault_probe 测试错误，不将错误说成成功。", [], ["code_execute", "list_artifacts", "fault_probe"]),
    ]
    return [{"key": key, "prompt": BASE + prompt,
             "skills": [{"key": s, "instructions": SKILLS[s]} for s in skills], "tools": tools}
            for key, prompt, skills, tools in specs]


def turn(message, **expect):
    return {"message": message, "expect": {"status": "completed", **expect}}


def generate(seed=20260923):
    rng = random.Random(seed)
    result = []
    ps = profiles()
    # 前100会话即包含长对话、产物、隔离、续接及异常，避免试跑只覆盖简单前缀。
    user_order = [10, 30, 0] + [i for i in range(50) if i not in (10, 30, 0)]
    for user in user_order:
        for agent, p in enumerate(ps):
            for phase in range(2):
                slot = user * 2 + phase
                cid = f"u{user:02d}-{p['key']}-{phase}"
                marker = hashlib.sha256(f"{seed}:{user}:{agent}".encode()).hexdigest()[:12].upper()
                original, corrected = f"PRJ-{marker}", f"FIX-{marker}"
                number = rng.randrange(100, 900)
                category = (
                    "greeting" if agent == 0 else "direct" if agent == 1 else
                    "format" if agent == 2 else "single_skill" if agent == 3 else
                    "multi_skill" if agent == 4 else
                    ("single_skill" if slot < 20 else "long_context") if agent == 5 else
                    ("cross_session" if slot < 60 else "isolation") if agent == 6 else
                    ("direct" if slot < 60 else "isolation") if agent == 7 else
                    ("isolation" if slot < 20 else "exception") if agent == 8 else
                    ("exception" if slot < 20 else "artifact"))
                fixture = {"case_key": "{batch}:" + cid, "value": f"VALUE-{marker}-{phase}"}
                additional_fixtures = []
                steps = []
                if user == 10 and p["key"] == "query" and phase == 1:
                    category = "parallel_read"
                if user == 10 and p["key"] == "single" and phase == 1:
                    category = "pause_resume"
                if user == 11 and p["key"] == "query" and phase == 1:
                    category = "reference_read"
                    reference_marker = f"REF-{marker}-{phase}"
                    fixture["value"] = "甲" * 15_000 + reference_marker + "乙" * 15_000
                if category == "greeting":
                    steps = [turn(x, max_tools=0, turn_mode="CONVERSATION") for x in ["你好", "谢谢", "今天心情不错，聊一句吧"]]
                elif category == "direct":
                    steps = [turn("你好", max_tools=0), turn(
                        f"最近一条已确认记录，case_key={fixture['case_key']}。", contains=[fixture["value"]], min_tools=1, turn_mode="DIRECT_REQUEST"),
                        turn("谢谢", max_tools=0)]
                elif category == "parallel_read":
                    second = {"case_key": fixture["case_key"] + "-b", "value": fixture["value"] + "-B"}
                    fixture = {"case_key": fixture["case_key"] + "-a", "value": fixture["value"] + "-A"}
                    additional_fixtures = [second]
                    steps = [turn(f"请在同一个并行批次中查询两条独立记录：case_key={fixture['case_key']} "
                                  f"和 case_key={second['case_key']}。分别发出 query_record 调用，报告两条记录的实际值。",
                                  contains=[fixture["value"], second["value"]], min_tools=2, min_parallel_tools=2)]
                elif category == "reference_read":
                    steps = [turn(f"查询记录 case_key={fixture['case_key']}。结果正文很大；查询后必须调用 "
                                  "read_context_reference 读取正文正中央，并报告中部唯一、以 REF- 开头的核验码。",
                                  contains=[reference_marker], min_tools=2,
                                  runtime_diagnostic_kind="REFERENCE_READ")]
                elif category == "format":
                    steps = [turn(f'仅输出JSON对象，code为"{original}"，quantity为整数{number}。',
                                  json_equals={"code": original, "quantity": number}, max_tools=0)]
                elif category == "pause_resume":
                    steps = [turn(f"持续协助我准备项目{original}。预算尚未确认，请使用 ask_user 向我询问预算，"
                                  "问题中带上项目代号，然后暂停并等待我答复，不能猜预算，也不要直接结束任务。",
                                  status="waiting_hitl", allow_hitl=True, contains=[original]),
                             turn(f"预算已确认是{number}元。请保存并回复项目代号和预算，本轮可以结束。",
                                  contains=[original, str(number)])]
                elif category in ("single_skill", "multi_skill", "long_context"):
                    steps = [turn(f"请使用资料收集技能，持续协助我准备项目，代号是{original}，预算{number}元。以后继续补充。", contains=["【资料】"], min_skills=1),
                             turn(f"纠正项目代号为{corrected}，预算不变，项目继续。", contains=[corrected])]
                    if category == "long_context":
                        steps += [turn(f"暂时不处理项目，第{i+1}个闲聊话题：{['天气','阅读','散步','音乐'][i%4]}，请简短回应。", max_tools=0)
                                  for i in range(28)]
                    if category == "multi_skill":
                        steps.append(turn("请切换到总结技能，总结我刚才确认的项目代号和预算。", contains=["【总结】", corrected, str(number)], min_skills=1))
                    else:
                        steps.append(turn("继续刚才的项目，只输出当前已确认的项目代号。不要解释，不要提及旧代号或历史纠正过程，不要猜测。", contains=[corrected], excludes=[original]))
                elif category == "cross_session":
                    steps = ([turn(f"持续协助我筹备项目，项目代号{original}，还有清单待下次补充。", contains=[original])]
                             if phase == 0 else [turn("继续上次筹备的项目，先告诉我已确认的项目代号。", contains=[original])])
                elif category == "isolation":
                    # 对其他用户/助手的唯一码做负向检查，随后建立本助手事实。
                    forbidden = ["PRJ-" + hashlib.sha256(f"{seed}:{u}:{a}".encode()).hexdigest()[:12].upper()
                                 for u, a in [(user-1, agent), (user, agent-1)] if u >= 0 and a >= 0]
                    steps = [turn("其他用户或其他助手记录的项目代号是什么？不知道就明确说不知道。", excludes=forbidden, max_tools=0),
                             turn(f"我们持续准备独立项目{original}，下次再补清单。", contains=[original]),
                             turn("本助手为我准备的项目代号是什么？", contains=[original], excludes=forbidden)]
                elif category == "exception":
                    mode = ["error", "delay", "disconnect", "duplicate"][slot % 4] if agent == 8 else ["error", "delay"][slot % 2]
                    if mode in ("error", "delay"):
                        steps = [turn(f"调用 fault_probe，case_key={fixture['case_key']}，mode={mode}，delay_seconds=1。报告真实结果。", min_tools=1, fault_mode=mode)]
                    elif mode == "disconnect":
                        steps = [turn(f"查询记录 case_key={fixture['case_key']}。", min_tools=1, contains=[fixture["value"]], disconnect=True)]
                    else:
                        steps = [turn(f"请立即写入测试记录：case_key={fixture['case_key']}，value={corrected}，request_key={cid}。这是本次明确写入指令。", min_tools=1, duplicate=True, write_value=corrected)]
                elif category == "artifact":
                    steps = [turn(f"请实际运行代码，生成result.txt，UTF-8内容精确为{original}，不要添加换行。作为可下载产物返回。", min_tools=1, artifact={"path": "result.txt", "text": original}),
                             turn("查询本会话产物清单并说明刚生成的文件名。", min_tools=1, contains=["result.txt"])]
                # 资料维护可合法追问；仍验证提交的挂起事件、问题内容和下一轮显式续答。
                if category in ("single_skill", "multi_skill", "long_context", "cross_session", "isolation"):
                    for step in steps:
                        step["expect"]["allow_hitl"] = True
                result.append({"id": cid, "user": user, "profile": p["key"], "phase": phase,
                               "category": category, "fixture": fixture, "additional_fixtures": additional_fixtures, "turns": steps})
    return {"schema": 1, "seed": seed, "profiles": ps, "cases": result}
