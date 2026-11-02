package com.skillforge.runtime.desktop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/** Windows Job Object 在业务进程恢复之前建立归属；监督进程独立核验活动成员归零。 */
final class DesktopWindowsCommandJob implements DesktopCommandProcesses.Supervisor {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String COMMAND_FILE = "command.txt";
    private static final String CHILD_SCRIPT_FILE = "command.ps1";
    private static final String SUPERVISOR_FILE = "supervisor.ps1";
    private static final String CANCEL_FILE = "cancel";
    private static final String RECEIPT_FILE = "receipt.json";
    private final Path directory;
    private final String nonce = UUID.randomUUID().toString();

    DesktopWindowsCommandJob(String command) throws IOException {
        directory = Files.createTempDirectory("noewise-command-").toRealPath();
        try {
            Files.writeString(directory.resolve(COMMAND_FILE), command, StandardCharsets.UTF_8);
            Files.writeString(directory.resolve(CHILD_SCRIPT_FILE), CHILD_SCRIPT, StandardCharsets.UTF_8);
            Files.writeString(directory.resolve(SUPERVISOR_FILE), SUPERVISOR_SCRIPT, StandardCharsets.UTF_8);
        } catch (IOException failure) {
            try {
                close();
            } catch (IOException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    @Override
    public List<String> launchCommand() {
        String systemRoot = System.getenv("SystemRoot");
        if (systemRoot == null) {
            throw new DesktopTools.ToolFailure("SHELL_UNAVAILABLE", "SystemRoot is required to locate Windows PowerShell.");
        }
        Path executable = Path.of(systemRoot, "System32", "WindowsPowerShell", "v1.0", "powershell.exe");
        return List.of(executable.toString(), "-NoLogo", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
                "-File", directory.resolve(SUPERVISOR_FILE).toString(), directory.toString(), nonce,
                Long.toString(ProcessHandle.current().pid()));
    }

    @Override
    public void requestStop() throws IOException {
        Files.writeString(directory.resolve(CANCEL_FILE), nonce, StandardCharsets.UTF_8);
    }

    @Override
    public boolean cleanupConfirmed(int supervisorExitCode) {
        try {
            Path receipt = directory.resolve(RECEIPT_FILE);
            if (!Files.isRegularFile(receipt) || Files.size(receipt) > 2048) {
                return false;
            }
            JsonNode json = JSON.readTree(Files.readString(receipt, StandardCharsets.UTF_8));
            boolean valid = json != null && json.isObject() && nonce.equals(json.path("nonce").asText())
                    && json.path("activeProcesses").isIntegralNumber() && json.path("activeProcesses").intValue() == 0
                    && json.path("exitCode").isIntegralNumber() && json.path("exitCode").intValue() == supervisorExitCode
                    && json.path("started").isBoolean();
            return valid;
        } catch (IOException failure) {
            return false;
        }
    }

    @Override
    public void close() throws IOException {
        // 只删除本工具创建的固定路径，不跟随工作目录或未知符号链接进行递归删除。
        for (String name : List.of(COMMAND_FILE, CHILD_SCRIPT_FILE, SUPERVISOR_FILE, CANCEL_FILE, RECEIPT_FILE)) {
            Files.deleteIfExists(directory.resolve(name));
        }
        Files.deleteIfExists(directory);
    }

    @Override
    public String tracking() {
        return "windows_job_object";
    }

    private static final String CHILD_SCRIPT = """
            $ErrorActionPreference = 'Stop'
            $ProgressPreference = 'SilentlyContinue'
            $OutputEncoding = [Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false)
            [Console]::InputEncoding = New-Object System.Text.UTF8Encoding($false)
            $LASTEXITCODE = $null
            try {
                $command = [IO.File]::ReadAllText((Join-Path $PSScriptRoot 'command.txt'), [Text.Encoding]::UTF8)
                & ([ScriptBlock]::Create($command))
                $commandSucceeded = $?
                if ($null -ne $LASTEXITCODE) { exit $LASTEXITCODE }
                if (-not $commandSucceeded) { exit 1 }
                exit 0
            } catch {
                [Console]::Error.WriteLine($_.Exception.Message)
                exit 1
            }
            """;

    private static final String SUPERVISOR_SCRIPT = """
            param([string]$privateDirectory, [string]$nonce, [int]$parentProcessId)
            $ErrorActionPreference = 'Stop'
            $ProgressPreference = 'SilentlyContinue'
            $OutputEncoding = [Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false)
            [Console]::InputEncoding = New-Object System.Text.UTF8Encoding($false)
            try {
                Add-Type -TypeDefinition @'
            using System;
            using System.ComponentModel;
            using System.Diagnostics;
            using System.IO;
            using System.Runtime.InteropServices;
            using System.Text;
            using System.Threading;

            public static class NoeWiseCommandJob {
                const uint CreateSuspended = 0x00000004;
                const uint CreateNewConsole = 0x00000010;
                const uint KillOnJobClose = 0x00002000;
                const uint StartUseStdHandles = 0x00000100;
                const uint WaitTimeout = 258;
                const int JobExtendedLimitInformation = 9;
                const int JobBasicAccountingInformation = 1;
                const int StopExitCode = 1223;
                const int BootstrapFailureExitCode = 125;
                const int CleanupFailureExitCode = 126;
                const int CleanupTimeoutMillis = 4000;
                const int PollMillis = 10;

                [StructLayout(LayoutKind.Sequential)]
                struct BasicLimit {
                    public long PerProcessUserTimeLimit, PerJobUserTimeLimit;
                    public uint LimitFlags;
                    public UIntPtr MinimumWorkingSetSize, MaximumWorkingSetSize;
                    public uint ActiveProcessLimit;
                    public UIntPtr Affinity;
                    public uint PriorityClass, SchedulingClass;
                }
                [StructLayout(LayoutKind.Sequential)]
                struct IoCounters { public ulong ReadOperationCount, WriteOperationCount, OtherOperationCount,
                    ReadTransferCount, WriteTransferCount, OtherTransferCount; }
                [StructLayout(LayoutKind.Sequential)]
                struct ExtendedLimit {
                    public BasicLimit BasicLimitInformation;
                    public IoCounters IoInfo;
                    public UIntPtr ProcessMemoryLimit, JobMemoryLimit, PeakProcessMemoryUsed, PeakJobMemoryUsed;
                }
                [StructLayout(LayoutKind.Sequential)]
                struct BasicAccounting {
                    public long TotalUserTime, TotalKernelTime, ThisPeriodTotalUserTime, ThisPeriodTotalKernelTime;
                    public uint TotalPageFaultCount, TotalProcesses, ActiveProcesses, TotalTerminatedProcesses;
                }
                [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
                struct StartupInfo {
                    public int cb;
                    public string lpReserved, lpDesktop, lpTitle;
                    public uint dwX, dwY, dwXSize, dwYSize, dwXCountChars, dwYCountChars, dwFillAttribute, dwFlags;
                    public ushort wShowWindow, cbReserved2;
                    public IntPtr lpReserved2, hStdInput, hStdOutput, hStdError;
                }
                [StructLayout(LayoutKind.Sequential)]
                struct ProcessInfo { public IntPtr hProcess, hThread; public uint processId, threadId; }
                [StructLayout(LayoutKind.Sequential)]
                struct SecurityAttributes { public int length; public IntPtr securityDescriptor; public int inheritHandle; }

                [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
                static extern IntPtr CreateJobObjectW(IntPtr attributes, string name);
                [DllImport("kernel32.dll", SetLastError = true)]
                static extern bool SetInformationJobObject(IntPtr job, int informationClass, ref ExtendedLimit information, uint length);
                [DllImport("kernel32.dll", SetLastError = true)]
                static extern bool QueryInformationJobObject(IntPtr job, int informationClass, out BasicAccounting information, uint length, IntPtr returnLength);
                [DllImport("kernel32.dll", SetLastError = true)]
                static extern bool AssignProcessToJobObject(IntPtr job, IntPtr process);
                [DllImport("kernel32.dll", SetLastError = true)]
                static extern bool TerminateJobObject(IntPtr job, uint exitCode);
                [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
                static extern bool CreateProcessW(string app, StringBuilder command, IntPtr processAttributes,
                    IntPtr threadAttributes, bool inheritHandles, uint flags, IntPtr environment, string currentDirectory,
                    ref StartupInfo startupInfo, out ProcessInfo processInformation);
                [DllImport("kernel32.dll", SetLastError = true)]
                static extern uint ResumeThread(IntPtr thread);
                [DllImport("kernel32.dll", SetLastError = true)]
                static extern bool TerminateProcess(IntPtr process, uint exitCode);
                [DllImport("kernel32.dll", SetLastError = true)]
                static extern bool GetExitCodeProcess(IntPtr process, out uint exitCode);
                [DllImport("kernel32.dll", SetLastError = true)]
                static extern uint WaitForSingleObject(IntPtr handle, uint milliseconds);
                [DllImport("kernel32.dll", SetLastError = true)]
                static extern bool CloseHandle(IntPtr handle);
                [DllImport("kernel32.dll", SetLastError = true)]
                static extern IntPtr OpenProcess(uint access, bool inheritHandle, int processId);
                [DllImport("kernel32.dll", SetLastError = true)]
                static extern IntPtr GetStdHandle(int id);
                [DllImport("kernel32.dll", SetLastError = true)]
                static extern bool SetHandleInformation(IntPtr handle, uint mask, uint flags);
                [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
                static extern IntPtr CreateFileW(string name, uint access, uint sharing, ref SecurityAttributes attributes,
                    uint creation, uint flags, IntPtr template);

                static void Require(bool success) { if (!success) throw new Win32Exception(Marshal.GetLastWin32Error()); }
                static bool EmptyJob(IntPtr job) {
                    BasicAccounting info;
                    Require(QueryInformationJobObject(job, JobBasicAccountingInformation, out info,
                        (uint)Marshal.SizeOf(typeof(BasicAccounting)), IntPtr.Zero));
                    return info.ActiveProcesses == 0;
                }
                static void Receipt(string directory, string nonce, bool started, int exitCode) {
                    string json = "{\\\"nonce\\\":\\\"" + nonce + "\\\",\\\"started\\\":" + (started ? "true" : "false")
                        + ",\\\"activeProcesses\\\":0,\\\"exitCode\\\":" + exitCode + "}";
                    using (var output = new FileStream(Path.Combine(directory, "receipt.json"), FileMode.CreateNew, FileAccess.Write, FileShare.Read)) {
                        byte[] bytes = new UTF8Encoding(false).GetBytes(json);
                        output.Write(bytes, 0, bytes.Length);
                        output.Flush(true);
                    }
                }
                public static int Run(string directory, string nonce, int parentProcessId) {
                    IntPtr job = IntPtr.Zero, input = IntPtr.Zero, parent = IntPtr.Zero;
                    ProcessInfo child = new ProcessInfo();
                    bool assigned = false, started = false, empty = false, unassignedStopped = true;
                    int exitCode = BootstrapFailureExitCode;
                    try {
                        parent = OpenProcess(0x00100000, false, parentProcessId);
                        Require(parent != IntPtr.Zero);
                        job = CreateJobObjectW(IntPtr.Zero, null);
                        Require(job != IntPtr.Zero);
                        ExtendedLimit limits = new ExtendedLimit();
                        limits.BasicLimitInformation.LimitFlags = KillOnJobClose;
                        Require(SetInformationJobObject(job, JobExtendedLimitInformation, ref limits,
                            (uint)Marshal.SizeOf(typeof(ExtendedLimit))));
                        if (!File.Exists(Path.Combine(directory, "cancel")) && WaitForSingleObject(parent, 0) == WaitTimeout) {
                            var attributes = new SecurityAttributes();
                            attributes.length = Marshal.SizeOf(typeof(SecurityAttributes));
                            attributes.inheritHandle = 1;
                            input = CreateFileW("NUL", 0x80000000, 3, ref attributes, 3, 0x80, IntPtr.Zero);
                            Require(input != IntPtr.Zero && input != new IntPtr(-1));
                            var startup = new StartupInfo();
                            startup.cb = Marshal.SizeOf(typeof(StartupInfo));
                            startup.dwFlags = StartUseStdHandles | 1;
                            startup.wShowWindow = 0;
                            startup.hStdInput = input;
                            startup.hStdOutput = GetStdHandle(-11);
                            startup.hStdError = GetStdHandle(-12);
                            Require(SetHandleInformation(startup.hStdOutput, 1, 1));
                            Require(SetHandleInformation(startup.hStdError, 1, 1));
                            string shell = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.System), "WindowsPowerShell", "v1.0", "powershell.exe");
                            string script = Path.Combine(directory, "command.ps1");
                            var command = new StringBuilder("\\\"" + shell + "\\\" -NoLogo -NoProfile -NonInteractive -ExecutionPolicy Bypass -File \\\"" + script + "\\\"");
                            Require(CreateProcessW(shell, command, IntPtr.Zero, IntPtr.Zero, true,
                                CreateSuspended | CreateNewConsole, IntPtr.Zero, Environment.CurrentDirectory, ref startup, out child));
                            Require(AssignProcessToJobObject(job, child.hProcess));
                            assigned = true;
                            Require(ResumeThread(child.hThread) != uint.MaxValue);
                            started = true;
                            uint waitResult;
                            while ((waitResult = WaitForSingleObject(child.hProcess, PollMillis)) == WaitTimeout) {
                                if (File.Exists(Path.Combine(directory, "cancel")) || WaitForSingleObject(parent, 0) == 0) {
                                    exitCode = StopExitCode;
                                    break;
                                }
                            }
                            if (exitCode != StopExitCode) {
                                Require(waitResult == 0);
                                uint nativeExit;
                                Require(GetExitCodeProcess(child.hProcess, out nativeExit));
                                exitCode = unchecked((int)nativeExit);
                            }
                        } else { exitCode = StopExitCode; }
                    } catch (Exception failure) {
                        Console.Error.WriteLine("Windows command supervisor: " + failure.GetType().Name);
                        exitCode = BootstrapFailureExitCode;
                    } finally {
                        if (child.hProcess != IntPtr.Zero && !assigned) {
                            unassignedStopped = TerminateProcess(child.hProcess, BootstrapFailureExitCode)
                                && WaitForSingleObject(child.hProcess, CleanupTimeoutMillis) == 0;
                        }
                        if (job != IntPtr.Zero) {
                            try {
                                Require(TerminateJobObject(job, StopExitCode));
                                var watch = Stopwatch.StartNew();
                                while (!(empty = EmptyJob(job)) && watch.ElapsedMilliseconds < CleanupTimeoutMillis) {
                                    Thread.Sleep(PollMillis);
                                }
                            } catch (Exception) { empty = false; }
                        }
                        if (child.hThread != IntPtr.Zero) CloseHandle(child.hThread);
                        if (child.hProcess != IntPtr.Zero) CloseHandle(child.hProcess);
                        if (input != IntPtr.Zero && input != new IntPtr(-1)) CloseHandle(input);
                        if (job != IntPtr.Zero) CloseHandle(job);
                        if (parent != IntPtr.Zero) CloseHandle(parent);
                    }
                    if (!empty || !unassignedStopped) return CleanupFailureExitCode;
                    Receipt(directory, nonce, started, exitCode);
                    return exitCode;
                }
            }
            '@
                exit ([NoeWiseCommandJob]::Run($privateDirectory, $nonce, $parentProcessId))
            } catch {
                [Console]::Error.WriteLine('Windows command supervisor bootstrap failed: ' + $_.Exception.GetType().Name)
                exit 125
            }
            """;
}
