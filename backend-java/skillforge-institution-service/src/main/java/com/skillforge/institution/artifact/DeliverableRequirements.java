package com.skillforge.institution.artifact;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import org.springframework.stereotype.Component;
import static com.skillforge.institution.artifact.DeliverableContract.*;

/** 仅在按需成果提交时校验实际清单，不解释自然语言或预先登记文件义务。 */
@Component
public final class DeliverableRequirements {
    /** 确定性清单约束一次反馈；不解析文件正文、不读取候选、不预留版本或变更输入。 */
    static void validateSelectionShape(List<Selection> selections) {
        var corrections = new ArrayList<OutputContractRejection.Correction>();
        for (int itemIndex = 0; itemIndex < selections.size(); itemIndex++) {
            var item = selections.get(itemIndex);
            String itemField = "deliverables[" + itemIndex + "]";
            if (item.representations().isEmpty()) correction(corrections, itemField + ".representations", List.of());
            else if (item.representations().stream().filter(Representation::primaryPreview).count() != 1)
                correction(corrections, itemField + ".representations[].primary_preview", List.of("true", "false"));
            var formats = new HashSet<String>();
            for (int index = 0; index < item.representations().size(); index++) {
                var representation = item.representations().get(index);
                String field = itemField + ".representations[" + index + "]";
                String format = representation.format();
                boolean validFormat = true;
                try {
                    if (format == null || !format.equals(DeliverableFilename.validateFormat(format))) validFormat = false;
                } catch (IllegalArgumentException invalid) { validFormat = false; }
                if (!validFormat) correction(corrections, field + ".format", List.of());
                if (validFormat && !formats.add(format)) correction(corrections, field + ".format", List.of());
                var roles = "zip".equals(format) ? List.of("bundle") : List.of("source", "export");
                if (representation.role() == null || !roles.contains(representation.role())) correction(corrections, field + ".role", roles);
                if (representation.files().isEmpty()) correction(corrections, field + ".files", List.of());
                validateReferences(representation.files(), field + ".files", corrections);
                validateReferences(representation.sourceFiles(), field + ".source_files", corrections);
                if ("export".equals(representation.role()) && representation.sourceFiles().isEmpty())
                    correction(corrections, field + ".source_files", List.of());
                if ("html".equals(format)) {
                    if (representation.entryPath() == null || representation.entryPath().isBlank()
                            || representation.files().stream().allMatch(file -> file.path() != null)
                                && !representation.files().stream().anyMatch(file -> representation.entryPath().equals(file.path())))
                        correction(corrections, field + ".entry_path", List.of());
                }
            }
        }
        if (!corrections.isEmpty()) throw new OutputContractRejection("OUTPUT_MANIFEST_INVALID", corrections,
                "交付尚未冻结，本次未预留成果版本。一次修正全部所列字段后提交完整清单；字段路径中的索引对应本次输入，不回显输入内容。"
                + "每项成果至少一个表示且恰有一个primary_preview=true，每个format仅一次；format为真实小写扩展名。"
                + "bundle仅用于zip，其他格式使用source或export；export必须提供真实转换输入source_files，不能伪造来源。"
                + "files不能为空，文件引用使用真实file_id或artifact_id/path；HTML的entry_path须与所选入口文件的真实path一致。"
                + "allowed_values为空表示需要补齐真实对象或消除组合冲突，不是删除必填字段；不必重新生成已核验正文。"
                + "无法交付时用空deliverables和非空abandon_reason显式放弃。");
    }

    private static void validateReferences(List<FileRef> references, String field,
            List<OutputContractRejection.Correction> corrections) {
        for (int index = 0; index < references.size(); index++) {
            var reference = references.get(index);
            if (reference.fileId() != null ? reference.fileId().isBlank()
                    : reference.artifactId() == null || reference.artifactId().isBlank()
                        || reference.path() == null || reference.path().isBlank())
                correction(corrections, field + "[" + index + "]", List.of());
        }
    }

    private static void correction(List<OutputContractRejection.Correction> corrections, String field, List<String> allowed) {
        corrections.add(new OutputContractRejection.Correction("*", field, allowed, false));
    }


    List<Planned> verifyForFinalization(List<Selection> selections) {
        validateSelectionShape(selections);
        return selections.stream().map(selection -> new Planned(selection.key(), selection.title(), selection.kind(),
                selection.baseRevisionId(), selection.representations().stream()
                        .map(representation -> new Format(representation.format())).toList())).toList();
    }
}
