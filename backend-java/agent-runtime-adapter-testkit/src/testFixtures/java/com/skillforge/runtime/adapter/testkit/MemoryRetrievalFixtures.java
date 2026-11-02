package com.skillforge.runtime.adapter.testkit;

import com.skillforge.runtime.api.MemoryFact;
import com.skillforge.runtime.api.MemoryRetrievalDiagnostics;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** 仅供受控 Port 夹具构造诊断；零分是测试输入，不代表生产检索算法或相关性。 */
public final class MemoryRetrievalFixtures {
    private MemoryRetrievalFixtures() { }

    /** 按夹具给定顺序冻结候选及预算采用集，不执行词法、向量、RRF 或去重算法。 */
    public static MemoryRetrievalDiagnostics controlledDiagnostics(
            List<MemoryFact> candidates, List<MemoryFact> selected) {
        var entries = new ArrayList<MemoryRetrievalDiagnostics.Candidate>();
        for (int index = 0; index < candidates.size(); index++) {
            MemoryFact fact = candidates.get(index);
            entries.add(new MemoryRetrievalDiagnostics.Candidate(fact.ref(), fact.revision(),
                    Optional.of(0d), Optional.of(index + 1), Optional.empty(), Optional.empty(), 0d));
        }
        return new MemoryRetrievalDiagnostics(MemoryRetrievalDiagnostics.Mode.LEXICAL, Optional.empty(),
                candidates.size(), 0, entries, selected.stream().map(fact -> fact.ref().factId()).toList());
    }
}
