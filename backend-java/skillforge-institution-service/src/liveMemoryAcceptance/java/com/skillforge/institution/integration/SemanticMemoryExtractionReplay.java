package com.skillforge.institution.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.agent.systemagent.MemoryExtractionCandidates;
import com.skillforge.institution.integration.SemanticMemoryExtractionReports.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** C专属的已采集输出重评；没有Spring、模型、数据库或原文件写入入口。 */
public final class SemanticMemoryExtractionReplay {
    private static final String LANE = "OFFLINE_CAPTURED_OUTPUT_REPLAY";
    private SemanticMemoryExtractionReplay() { }

    public static void main(String[] args) throws Exception {
        require(args.length == 2, "source_and_new_output_required");
        Path source = Path.of(args[0]).normalize(), output = Path.of(args[1]).normalize();
        require(source.isAbsolute() && output.isAbsolute(), "absolute_directories_required");
        require(!output.startsWith(source) && !source.startsWith(output), "source_and_output_must_be_separate");
        require(!Files.exists(output), "new_output_directory_required");
        var json = SemanticMemoryExtractionEvaluation.reportJson(new ObjectMapper());
        var parser = new MemoryExtractionCandidates(json);
        Path captureFile = source.resolve("capture.json"); byte[] captureBytes = Files.readAllBytes(captureFile);
        Capture capture = json.readValue(captureBytes, Capture.class);
        require("COMPLETED".equals(capture.state) && capture.plannedSamples == 32 && capture.modelCallsAttempted == 32,
                "complete_original_32_matrix_required");
        String captureHash = digest(captureBytes), started = Instant.now().toString();
        Files.createDirectory(output);
        var splits = new ArrayList<ReplaySplit>();
        for (String split : List.of("dev", "heldout")) {
            Path sourceFolder = source.resolve(split), destination = output.resolve(split);
            Path batchFile = sourceFolder.resolve("batch.json"); byte[] batchBytes = Files.readAllBytes(batchFile);
            Batch original = json.readValue(batchBytes, Batch.class);
            byte[] suiteBytes = Files.readAllBytes(sourceFolder.resolve("suite.json"));
            Suite suite = json.readValue(suiteBytes, Suite.class);
            SemanticMemoryExtractionEvaluation.validateSuite(suite, parser);
            String suiteHash = digest(suiteBytes), batchHash = digest(batchBytes);
            require("COMPLETED".equals(original.outcome) && original.plannedSamples == 16 && original.samples.size() == 16,
                    "complete_original_split_required");
            require(suiteHash.equals(original.suiteHash) && suiteHash.equals(capture.suiteHashes.forSplit(split)), "source_suite_digest");
            var sourceCapture = new SourceCapture(captureFile.toString(), captureHash, capture.state, capture.protocolOutcome,
                    capture.protocolFailures, batchFile.toString(), batchHash, suiteHash);
            var replay = new Batch(); replay.suiteId = original.suiteId; replay.suiteHash = suiteHash;
            replay.batchId = "semantic-c-replay-" + split + "-" + UUID.randomUUID(); replay.startedAt = Instant.now().toString();
            replay.plannedSamples = 16; replay.lane = LANE; replay.model = original.model; replay.prompt = original.prompt;
            replay.budget = new Budget(0, 0, 0, 0, 0, 0, "OFFLINE_NO_MODEL_OR_DATABASE_CALLS");
            replay.callsAttempted = 0; replay.chargedOrReservedTokens = 0L; replay.sourceCapture = sourceCapture;
            replay.usageAccounting = "NO_NEW_USAGE_OR_COST_ORIGINAL_PROVIDER_USAGE_IN_SOURCE_SAMPLE";
            replay.sourceBindings = SemanticMemoryExtractionEvaluation.sourceBindings();
            Files.createDirectory(destination); Files.write(destination.resolve("suite.json"), suiteBytes, StandardOpenOption.CREATE_NEW);
            for (int index = 0; index < suite.scenarios().size(); index++) {
                Scenario scenario = suite.scenarios().get(index); SampleIndex entry = original.samples.get(index);
                String fileName = "sample-" + scenario.id() + "-1.json";
                require(entry.sampleId().equals(scenario.id() + "-1") && entry.path().equals(fileName), "original_sample_position");
                Path sourceFile = sourceFolder.resolve(fileName); byte[] sampleBytes = Files.readAllBytes(sourceFile);
                Sample old = json.readValue(sampleBytes, Sample.class);
                require(old.sampleId.equals(entry.sampleId()) && old.scenario.equals(scenario) && old.stages.size() == 1,
                        "original_sample_identity");
                Stage received = old.stages.getFirst();
                require(Objects.equals(old.inputSha256, digest(parser.encodeInput(received.input).getBytes(StandardCharsets.UTF_8))), "original_input_digest");
                require(received.input.equals(scenario.input()) && received.rawOutput != null, "original_input_or_output_missing");
                require(Objects.equals(received.rawOutputSha256, digest(received.rawOutput.getBytes(StandardCharsets.UTF_8))), "original_output_digest");
                var sample = new Sample(); sample.sampleId = old.sampleId; sample.scenario = scenario; sample.lane = LANE;
                sample.inputSha256 = old.inputSha256;
                sample.sourceSample = new SourceSample(sourceFile.toString(), digest(sampleBytes), batchHash, old.inputSha256,
                        received.runId, received.terminal, received.errorCode, received.invocationRef, received.rawOutputSha256,
                        received.usage, received.durationMs, old.attempt);
                var stage = new Stage(); stage.kind = LANE; stage.input = received.input;
                stage.runId = received.runId; stage.systemAgentId = received.systemAgentId; stage.systemAgentVersion = received.systemAgentVersion;
                stage.configurationRef = received.configurationRef; stage.invocationRef = received.invocationRef;
                stage.rawOutput = received.rawOutput; stage.rawOutputSha256 = received.rawOutputSha256;
                stage.usage = Usage.observed(null, null); sample.stages.add(stage);
                long parseStarted = System.nanoTime();
                try {
                    stage.parsedCandidates = parser.parse(received.rawOutput, received.input); stage.terminal = "PARSED";
                    sample.assertions.add(new Assertion("EXTRACTION", "C_OFFLINE_PARSER_RESULT", "DETERMINISTIC", "PASS",
                            json.valueToTree("PARSED"), json.valueToTree(stage.terminal)));
                    SemanticMemoryExtractionEvaluation.semanticAssertions(json, sample.assertions, scenario.expected(), stage.parsedCandidates);
                } catch (IllegalArgumentException rejected) {
                    stage.terminal = "REJECTED"; stage.errorCode = rejected.getMessage(); sample.outcome = "FAIL";
                    replay.protocolFailures++;
                    sample.assertions.add(new Assertion("EXTRACTION", "C_OFFLINE_PARSER_RESULT", "DETERMINISTIC", "FAIL",
                            json.valueToTree("PARSED"), json.valueToTree(stage.errorCode)));
                }
                stage.durationMs = (System.nanoTime() - parseStarted) / 1_000_000;
                sample.finishedAt = Instant.now().toString(); write(json, destination.resolve(fileName), sample);
                replay.samples.add(new SampleIndex(sample.sampleId, fileName, sample.outcome));
            }
            replay.outcome = "COMPLETED"; replay.protocolOutcome = replay.protocolFailures == 0 ? "PASS" : "FAIL";
            replay.finishedAt = Instant.now().toString(); Path replayBatch = destination.resolve("batch.json"); write(json, replayBatch, replay);
            splits.add(new ReplaySplit(split, sourceCapture, digest(Files.readAllBytes(replayBatch)), replay.samples.size(),
                    replay.protocolFailures, replay.protocolOutcome));
        }
        int failed = splits.stream().mapToInt(ReplaySplit::protocolFailures).sum();
        write(json, output.resolve("replay.json"), new ReplayCapture(1, LANE, captureFile.toString(), captureHash,
                32, 0, 0, failed, failed == 0 ? "PASS" : "FAIL", List.copyOf(splits), started, Instant.now().toString(),
                "ORIGINAL_REAL_MODEL_CAPTURE_CURRENT_OFFLINE_PARSER_ONLY_KNOWN_CASES_NOT_BLIND_AGENT_REVIEW_REQUIRED"));
        require(failed == 0, "offline_protocol_gate_failed_original_evidence_preserved");
    }

    private static void write(ObjectMapper json, Path file, Object value) throws Exception {
        Files.writeString(file, json.writerWithDefaultPrettyPrinter().writeValueAsString(value) + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    }
    private static String digest(byte[] value) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
    private static void require(boolean condition, String reason) {
        if (!condition) throw new IllegalArgumentException("semantic_c_replay_" + reason);
    }
}
