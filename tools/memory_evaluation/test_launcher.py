"""启动器边界测试；所有 Gradle 调用均使用 Mock，不运行实际验收或收费请求。"""

import contextlib
import io
import json
import tempfile
import unittest
import hashlib
from types import SimpleNamespace
from pathlib import Path
from unittest.mock import patch

import run_acceptance


class LauncherTest(unittest.TestCase):
    """验证显式入口、四键字面解析、环境隔离、秘密输出和新批次保护。"""

    def setUp(self):
        """创建自己的配置和证据临时目录，避免读取真实 .env。"""
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.env_file = self.root / ".env"
        self.env_file.write_text("SPRING_DATASOURCE_URL='jdbc:test://local/db'\n"
                                 "PLATFORM_DATASOURCE_USERNAME= test_user\n"
                                 "PLATFORM_DATASOURCE_PASSWORD=\"literal-$(`do-not-run`)\"\n"
                                 "SECRET_KEY=literal-${OTHER_SECRET}\n"
                                 "LIVE_MEMORY_SOURCE_MODEL_ID=948372615012345678\n"
                                 "LIVE_MEMORY_SOURCE_MODEL_REVISION=3\n"
                                 "UNRELATED_DATABASE_URL=must-not-pass\n", encoding="utf-8")

    def tearDown(self):
        """只清理当前用例拥有的临时资源。"""
        self.temp.cleanup()

    def test_env_literals_are_not_executed_or_interpolated(self):
        """完整引号和未引号值按原样解析，其他键不进入来源环境。"""
        values = run_acceptance.read_source_env(self.env_file)
        self.assertEqual(set(values), set(run_acceptance.SOURCE_KEYS + run_acceptance.MODEL_KEYS))
        self.assertEqual(values["PLATFORM_DATASOURCE_PASSWORD"], "literal-$(`do-not-run`)")
        self.assertEqual(values["SECRET_KEY"], "literal-${OTHER_SECRET}")

    def test_missing_or_duplicate_source_fields_never_print_values(self):
        """拒绝缺字段和重复定义，错误说明只包含字段名。"""
        self.env_file.write_text("SECRET_KEY=highly-private-value\nSECRET_KEY=second-private-value\n")
        with self.assertRaises(run_acceptance.LaunchError) as error:
            run_acceptance.read_source_env(self.env_file)
        self.assertEqual(str(error.exception), "重复字段：SECRET_KEY")
        self.env_file.write_text("SECRET_KEY=highly-private-value\n")
        with self.assertRaises(run_acceptance.LaunchError) as error:
            run_acceptance.read_source_env(self.env_file)
        self.assertNotIn("highly-private-value", str(error.exception))
        self.assertIn("SPRING_DATASOURCE_URL", str(error.exception))

    def test_host_environment_is_not_a_business_config_channel(self):
        """只保留工具链键；旧收费开关和共享数据库等宿主环境都被剔除。"""
        host = {"Path": "tools", "JAVA_HOME": "jdk", "SPRING_DATASOURCE_URL": "shared-db",
                "SECRET_KEY": "shared-secret", "SKILLFORGE_LIVE_MEMORY_ACCEPTANCE": "1",
                "LIVE_MEMORY_SOURCE_MODEL_ID": "wrong", "SANDBOX_SERVICE_TOKEN": "token"}
        offline = run_acceptance.safe_environment(None, None, Path("suite.json"), "offline", host)
        self.assertEqual(offline, {"Path": "tools", "JAVA_HOME": "jdk",
                                   "GRADLE_USER_HOME": str(Path(run_acceptance.DEFAULT_GRADLE_HOME).absolute())})
        live = run_acceptance.safe_environment(run_acceptance.read_source_env(self.env_file), self.root / "live", Path("suite.json"), "live", host)
        self.assertEqual(live["LIVE_MEMORY_SOURCE_MODEL_ID"], "948372615012345678")
        self.assertEqual(live["LIVE_MEMORY_SOURCE_MODEL_REVISION"], "3")
        self.assertEqual(live["SPRING_DATASOURCE_URL"], "jdbc:test://local/db")
        self.assertNotIn("SANDBOX_SERVICE_TOKEN", live)

    def test_default_invokes_only_offline_task_without_env_file(self):
        """默认入口只选择离线任务，完全不读取环境文件。"""
        with patch.object(run_acceptance.subprocess, "run") as invoke, patch.object(run_acceptance, "read_source_env") as read:
            invoke.return_value.returncode = 0
            self.assertEqual(run_acceptance.main([]), 0)
            self.assertIn(":skillforge-institution-service:memoryEvaluationOfflineTest", invoke.call_args.args[0])
            self.assertIn("--no-parallel", invoke.call_args.args[0])
            self.assertIn("--max-workers=1", invoke.call_args.args[0])
            self.assertIn("--no-daemon", invoke.call_args.args[0])
            read.assert_not_called()
            self.assertNotIn("SKILLFORGE_LIVE_MEMORY_ACCEPTANCE", invoke.call_args.kwargs["env"])
            self.assertEqual(invoke.call_args.kwargs["env"]["GRADLE_USER_HOME"], str(Path(run_acceptance.DEFAULT_GRADLE_HOME).absolute()))

    def test_explicit_gradle_home_is_consistent_without_arbitrary_java_options(self):
        """环境与命令使用同一个缓存，不继承会改变 JVM 语义的宿主选项。"""
        home = self.root / "ascii-gradle"
        host = {"PATH": "tools", "GRADLE_USER_HOME": "other-cache", "GRADLE_OPTS": "unsafe-opts",
                "JAVA_TOOL_OPTIONS": "unsafe-tools", "JDK_JAVA_OPTIONS": "unsafe-jdk", "_JAVA_OPTIONS": "unsafe-java"}
        environment = run_acceptance.safe_environment(None, None, Path("suite.json"), "offline", host, home)
        self.assertEqual(environment["GRADLE_USER_HOME"], str(home.absolute()))
        self.assertEqual(set(environment), {"PATH", "GRADLE_USER_HOME"})

    def test_ascii_junction_alias_is_preserved_in_environment_command_and_receipt(self):
        """模拟 resolve 穿过 Junction 指向中文目录，启动全链仍使用原 ASCII 别名。"""
        alias = Path(run_acceptance.DEFAULT_GRADLE_HOME).absolute()
        target = Path("C:/Users/中文用户/.gradle").absolute()
        original_resolve = Path.resolve
        redirected = []

        def junction_resolve(path, *args, **kwargs):
            """只有缓存别名模拟重定向，其余测试文件按真实路径处理。"""
            if path.absolute() == alias:
                redirected.append(path)
                return target
            return original_resolve(path, *args, **kwargs)

        evidence = self.root / "junction-live"
        with (patch.object(Path, "resolve", junction_resolve),
              patch.object(run_acceptance.subprocess, "run") as invoke,
              contextlib.redirect_stdout(io.StringIO())):
            invoke.side_effect = self.mock_live_run
            self.assertEqual(run_acceptance.main(["live", "--execute-live", "--env-file", str(self.env_file),
                                                  "--evidence-dir", str(evidence), "--gradle-user-home", str(alias)]), 0)
        command = invoke.call_args.args[0]
        self.assertEqual(command[command.index("--gradle-user-home") + 1], str(alias))
        self.assertEqual(invoke.call_args.kwargs["env"]["GRADLE_USER_HOME"], str(alias))
        receipt = json.loads((evidence / "launcher-start.json").read_text(encoding="utf-8"))
        self.assertEqual(receipt["gradleUserHome"], str(alias))
        self.assertEqual(redirected, [])
        self.assertNotIn(str(target), json.dumps(receipt))

    def test_utf8_locale_toolchain_settings_are_preserved(self):
        """保留宿主区域编码设置，同时禁止业务连接和任意 JVM 选项透传。"""
        host = {"PATH": "tools", "LANG": "C.UTF-8", "LC_ALL": "C.UTF-8", "LC_CTYPE": "C.UTF-8",
                "JAVA_TOOL_OPTIONS": "must-not-pass", "GRADLE_OPTS": "must-not-pass",
                "SPRING_DATASOURCE_URL": "shared-db"}
        environment = run_acceptance.safe_environment(None, None, Path("suite.json"), "offline", host)
        for key in ("LANG", "LC_ALL", "LC_CTYPE"):
            self.assertEqual(environment[key], "C.UTF-8")
        self.assertNotIn("JAVA_TOOL_OPTIONS", environment)
        self.assertNotIn("GRADLE_OPTS", environment)
        self.assertNotIn("SPRING_DATASOURCE_URL", environment)

    def test_live_gate_precedes_source_read_and_process_start(self):
        """漏显式 live 开关时不读取秘密，不创建目录，也不启动 Wrapper。"""
        evidence = self.root / "live"
        with (patch.object(run_acceptance.subprocess, "run") as invoke,
              patch.object(run_acceptance, "read_source_env") as read,
              contextlib.redirect_stderr(io.StringIO())):
            self.assertEqual(run_acceptance.main(["live", "--env-file", str(self.env_file), "--evidence-dir", str(evidence)]), 2)
            invoke.assert_not_called()
            read.assert_not_called()
        self.assertFalse(evidence.exists())

    def test_live_launch_uses_four_keys_and_safe_receipts(self):
        """显式入口仅把四键注入进程，公开启动信息和收据不包含配置值。"""
        evidence, output = self.root / "live", io.StringIO()
        with patch.object(run_acceptance.subprocess, "run") as invoke, contextlib.redirect_stdout(output):
            invoke.side_effect = self.mock_live_run
            self.assertEqual(run_acceptance.main(["live", "--execute-live", "--env-file", str(self.env_file),
                                                  "--evidence-dir", str(evidence)]), 0)
            environment = invoke.call_args.kwargs["env"]
            self.assertTrue(set(run_acceptance.SOURCE_KEYS) <= environment.keys())
            self.assertNotIn("UNRELATED_DATABASE_URL", environment)
            self.assertTrue(Path(environment["LIVE_MEMORY_SUITE_FILE"]).is_absolute())
            self.assertEqual(invoke.call_args.kwargs["stderr"], run_acceptance.subprocess.STDOUT)
        receipt = json.loads((evidence / "launcher-start.json").read_text(encoding="utf-8"))
        encoded = output.getvalue() + json.dumps(receipt)
        values = run_acceptance.read_source_env(self.env_file)
        for value in (values[key] for key in run_acceptance.SOURCE_KEYS):
            self.assertNotIn(value, encoded)
        self.assertEqual(receipt["model"], {"id": "948372615012345678", "revision": 3})
        self.assertTrue((evidence / "launcher-finish.json").is_file())

    def mock_live_run(self, command, **kwargs):
        """生成真实格式的七十二位置证据，模拟 Wrapper 而不访问外部服务。"""
        environment = kwargs["env"]
        self.write_batch(Path(environment["LIVE_MEMORY_EVIDENCE_DIR"]), Path(environment["LIVE_MEMORY_SUITE_FILE"]))
        return SimpleNamespace(returncode=0)

    def write_batch(self, evidence, suite_path, infrastructure=False, missing_last=False, outcome="UNKNOWN"):
        """测试证据与真实冻结套件绑定，语义未知不会被转换为通过。"""
        suite_bytes = suite_path.read_bytes()
        suite = json.loads(suite_bytes)
        entries = []
        for scenario in suite["scenarios"]:
            for repeat in range(1, suite["repeats"] + 1):
                sample_id = f'{scenario["id"]}-{repeat}'
                entries.append({"sampleId": sample_id, "path": f"sample-{sample_id}.json", "outcome": outcome})
                sample = {"schemaVersion": 1, "sampleId": sample_id, "scenario": scenario, "repeat": repeat,
                          "outcome": outcome, "failures": [{"category": "INFRASTRUCTURE", "code": "LIVE_MEMORY_HTTP_500"}]
                          if infrastructure and len(entries) == 1 else []}
                (evidence / entries[-1]["path"]).write_text(json.dumps(sample), encoding="utf-8")
        if missing_last:
            (evidence / entries[-1]["path"]).unlink()
            entries[-1]["outcome"] = "NOT_RUN"
        batch = {"schemaVersion": 1, "suiteId": suite["suiteId"], "suiteHash": hashlib.sha256(suite_bytes).hexdigest(),
                 "plannedSamples": 72, "samples": entries, "outcome": "COMPLETED", "stopCode": None}
        (evidence / "batch.json").write_text(json.dumps(batch), encoding="utf-8")

    def test_model_selection_rejects_sql_text_and_out_of_range_values(self):
        """来源选择必须为正整数参数；错误不含提供的非法值。"""
        original = self.env_file.read_text(encoding="utf-8")
        for invalid in ("1 OR 1=1", "0", "-1", "9223372036854775808"):
            self.env_file.write_text(original.replace("948372615012345678", invalid), encoding="utf-8")
            with self.assertRaises(run_acceptance.LaunchError) as error:
                run_acceptance.read_source_env(self.env_file)
            self.assertEqual(str(error.exception), "无效模型选择字段：LIVE_MEMORY_SOURCE_MODEL_ID")
        self.env_file.write_text(original.replace("REVISION=3", "REVISION=2147483648"), encoding="utf-8")
        with self.assertRaises(run_acceptance.LaunchError):
            run_acceptance.read_source_env(self.env_file)

    def test_execution_summary_keeps_semantic_unknown_and_rejects_infrastructure_success(self):
        """Gradle 退出零不能将基础设施失败或缺样本冒充完整验收。"""
        suite = Path(run_acceptance.__file__).with_name("suite.json")
        for name, infrastructure, missing in (("complete", False, False), ("infra", True, False), ("partial", False, True)):
            evidence = self.root / name
            evidence.mkdir()
            self.write_batch(evidence, suite, infrastructure, missing)
            summary = run_acceptance.execution_summary(evidence, suite)
            self.assertEqual(summary["executionComplete"], name == "complete")
            self.assertEqual(summary["plannedSamples"], 72)
            self.assertEqual(summary["acceptanceOutcome"], "UNKNOWN")
        self.assertEqual(run_acceptance.execution_summary(self.root / "missing", suite)["executionComplete"], False)

    def test_live_zero_gradle_exit_with_missing_evidence_returns_failure(self):
        """证据缺失明确返回三并记录原 Gradle 退出码，禁止只看任务绿灯。"""
        evidence = self.root / "no-batch"
        with patch.object(run_acceptance.subprocess, "run") as invoke, contextlib.redirect_stdout(io.StringIO()):
            invoke.return_value.returncode = 0
            self.assertEqual(run_acceptance.main(["live", "--execute-live", "--env-file", str(self.env_file),
                                                  "--evidence-dir", str(evidence)]), 3)
        receipt = json.loads((evidence / "launcher-finish.json").read_text(encoding="utf-8"))
        self.assertEqual(receipt["gradleReturnCode"], 0)
        self.assertFalse(receipt["executionComplete"])

    def test_new_evidence_and_dry_run_do_not_overwrite(self):
        """旧证据拒绝重跑；预演既不创建目录也不调用 Gradle。"""
        evidence = self.root / "browser"
        with patch.object(run_acceptance.subprocess, "run") as invoke, contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(run_acceptance.main(["browser", "--evidence-dir", str(evidence), "--dry-run"]), 0)
            invoke.assert_not_called()
            self.assertFalse(evidence.exists())
        evidence.mkdir()
        with patch.object(run_acceptance.subprocess, "run") as invoke, contextlib.redirect_stderr(io.StringIO()):
            self.assertEqual(run_acceptance.main(["browser", "--evidence-dir", str(evidence)]), 2)
            invoke.assert_not_called()


if __name__ == "__main__":
    unittest.main()
