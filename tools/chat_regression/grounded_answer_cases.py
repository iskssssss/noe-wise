"""最终解释的独立数据关系样本；事实预期不注入生产提示或用户问题。"""
from complex_artifact_case import TURNS, oracle

QUESTIONS = [
    TURNS[0] + "说明去重前后参与统计的行数和合计如何变化。",
    TURNS[1] + "请比较本版与上一版的原始合计、去重合计、ledger合计和对账差额，明确各自统计范围。",
]
CASES = {
    "P1-ZERO": {
        "orders": b"id,total\nA,100\nB,0\nB,0\nC,50\n",
        "ledger": b"id,total\nA,100\nB,0\nC,40\n",
    },
    "P1-NEGATIVE": {
        "orders": b"id,total\nA,100\nB,-30\nB,-30\nC,50\n",
        "ledger": b"id,total\nA,100\nB,-30\nC,40\n",
    },
}


def expectations(contents):
    """独立十进制oracle以及版本变化；不解析或接受模型自报的预期。"""
    reports = [oracle(contents), oracle(contents, {"A", "B"})]
    fields = ("raw_total", "unique_total", "ledger_total", "difference")
    return {
        "reports": reports,
        "deduplication_changes": [row["unique_total"] - row["raw_total"] for row in reports],
        "revision_changes": {field: reports[1][field] - reports[0][field] for field in fields},
        "semantic_review": "PENDING: compare actual observed results, final text, scope and version claims",
    }
