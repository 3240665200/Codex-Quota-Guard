using System;
using System.Collections;
using System.Collections.Generic;
using System.IO;
using System.Runtime.InteropServices;
using System.Text;
using Microsoft.Win32.SafeHandles;

namespace Pingche.Preview {
    public sealed class OwnedChildProcess : IDisposable {
        readonly object gate = new object();
        OwnedKernelHandle process;
        public int Id { get; private set; }
        public DateTime CreatedUtc { get; private set; }

        internal OwnedChildProcess(OwnedKernelHandle handle, int id, DateTime createdUtc) {
            process = handle; Id = id; CreatedUtc = createdUtc;
        }

        public bool HasExited {
            get { lock (gate) { return Wait(0); } }
        }

        public int? ExitCode {
            get {
                lock (gate) {
                    if (!Wait(0)) return null;
                    uint code;
                    if (!OwnedProcessNative.GetExitCodeProcess(process, out code))
                        throw new InvalidOperationException("PREVIEW_CHILD_EXIT_QUERY_FAILED");
                    return unchecked((int)code);
                }
            }
        }

        public bool WaitForExit(int milliseconds) {
            if (milliseconds < -1) throw new ArgumentOutOfRangeException("milliseconds");
            lock (gate) { return Wait(unchecked((uint)milliseconds)); }
        }

        bool Wait(uint milliseconds) {
            if (process == null) throw new ObjectDisposedException("OwnedChildProcess");
            uint result = OwnedProcessNative.WaitForSingleObject(process, milliseconds);
            if (result == 0) return true;
            if (result == 258) return false;
            throw new InvalidOperationException("PREVIEW_CHILD_WAIT_FAILED");
        }

        public void Dispose() {
            lock (gate) {
                if (process != null) { process.Dispose(); process = null; }
            }
        }
    }

    // The unnamed, noninheritable Job owns only processes assigned by Spawn.
    public sealed class OwnedChildJob : IDisposable {
        readonly object gate = new object();
        OwnedKernelHandle job;

        public OwnedChildJob() {
            var handle = new OwnedKernelHandle(OwnedProcessNative.CreateJobObjectW(IntPtr.Zero, null));
            try {
                if (handle.IsInvalid) throw new InvalidOperationException("PREVIEW_CHILD_JOB_CREATE_FAILED");
                var limits = new OwnedProcessNative.ExtendedLimits();
                limits.Basic.LimitFlags = 0x2000; // JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE
                if (!OwnedProcessNative.SetInformationJobObject(handle, 9, ref limits,
                        (uint)Marshal.SizeOf(typeof(OwnedProcessNative.ExtendedLimits))))
                    throw new InvalidOperationException("PREVIEW_CHILD_JOB_CONFIGURE_FAILED");
                job = handle;
                handle = null;
            } finally { if (handle != null) handle.Dispose(); }
        }

        public OwnedChildProcess Spawn(string executable, string arguments, string cwd,
                IDictionary environment, string stdout, string stderr) {
            if (String.IsNullOrEmpty(executable) || !Path.IsPathRooted(executable) || executable.IndexOf('\0') >= 0)
                throw new ArgumentException("PREVIEW_CHILD_EXECUTABLE_INVALID");
            if (String.IsNullOrEmpty(cwd) || !Path.IsPathRooted(cwd) || cwd.IndexOf('\0') >= 0)
                throw new ArgumentException("PREVIEW_CHILD_DIRECTORY_INVALID");
            if (arguments == null || arguments.IndexOf('\0') >= 0)
                throw new ArgumentException("PREVIEW_CHILD_ARGUMENTS_INVALID");
            if (String.IsNullOrEmpty(stdout) || !Path.IsPathRooted(stdout) || stdout.IndexOf('\0') >= 0 ||
                    String.IsNullOrEmpty(stderr) || !Path.IsPathRooted(stderr) || stderr.IndexOf('\0') >= 0)
                throw new ArgumentException("PREVIEW_CHILD_LOG_PATH_INVALID");
            if (environment == null) throw new ArgumentNullException("environment");

            // The caller validates both log paths against its private run directory.
            lock (gate) {
                RequireJob();
                OwnedKernelHandle input = null, output = null, error = null, child = null, thread = null;
                IntPtr environmentBlock = IntPtr.Zero, attributes = IntPtr.Zero, handleList = IntPtr.Zero;
                int environmentBytes = 0;
                bool attributesInitialized = false, returned = false;
                try {
                    environmentBlock = OwnedProcessNative.EnvironmentBlock(environment, out environmentBytes);
                    var security = new OwnedProcessNative.SecurityAttributes();
                    security.Length = Marshal.SizeOf(typeof(OwnedProcessNative.SecurityAttributes));
                    security.InheritHandle = true;
                    input = OpenFile("NUL", 0x80000000, 3, 3, ref security);
                    output = OpenFile(stdout, 0x40000000, 1, 1, ref security);
                    error = OpenFile(stderr, 0x40000000, 1, 1, ref security);

                    // bInheritHandles is required for stdio, so explicitly allow only these handles.
                    IntPtr attributeBytes = IntPtr.Zero;
                    OwnedProcessNative.InitializeProcThreadAttributeList(IntPtr.Zero, 1, 0, ref attributeBytes);
                    if (attributeBytes == IntPtr.Zero)
                        throw new InvalidOperationException("PREVIEW_CHILD_HANDLE_LIST_FAILED");
                    attributes = Marshal.AllocHGlobal(attributeBytes);
                    if (!OwnedProcessNative.InitializeProcThreadAttributeList(attributes, 1, 0, ref attributeBytes))
                        throw new InvalidOperationException("PREVIEW_CHILD_HANDLE_LIST_FAILED");
                    attributesInitialized = true;
                    handleList = Marshal.AllocHGlobal(IntPtr.Size * 3);
                    Marshal.WriteIntPtr(handleList, 0, input.DangerousGetHandle());
                    Marshal.WriteIntPtr(handleList, IntPtr.Size, output.DangerousGetHandle());
                    Marshal.WriteIntPtr(handleList, IntPtr.Size * 2, error.DangerousGetHandle());
                    if (!OwnedProcessNative.UpdateProcThreadAttribute(attributes, 0, new IntPtr(0x20002),
                            handleList, new IntPtr(IntPtr.Size * 3), IntPtr.Zero, IntPtr.Zero))
                        throw new InvalidOperationException("PREVIEW_CHILD_HANDLE_LIST_FAILED");

                    var startup = new OwnedProcessNative.StartupInfoEx();
                    startup.Info.Size = Marshal.SizeOf(typeof(OwnedProcessNative.StartupInfoEx));
                    startup.Info.Flags = 0x100; // STARTF_USESTDHANDLES
                    startup.Info.Input = input.DangerousGetHandle();
                    startup.Info.Output = output.DangerousGetHandle();
                    startup.Info.Error = error.DangerousGetHandle();
                    startup.Attributes = attributes;
                    OwnedProcessNative.ProcessInformation information;
                    var command = new StringBuilder(OwnedProcessNative.Quote(executable) +
                        (arguments.Length == 0 ? String.Empty : " " + arguments));
                    // Suspended until it has been assigned to this private Job. No breakaway fallback.
                    if (!OwnedProcessNative.CreateProcessW(executable, command, IntPtr.Zero, IntPtr.Zero, true,
                            0x00000004 | 0x08000000 | 0x00000400 | 0x00080000, environmentBlock,
                            cwd, ref startup, out information))
                        throw new InvalidOperationException("PREVIEW_CHILD_CREATE_FAILED");
                    child = new OwnedKernelHandle(information.Process);
                    thread = new OwnedKernelHandle(information.Thread);
                    if (!OwnedProcessNative.AssignProcessToJobObject(job, child))
                        throw new InvalidOperationException("PREVIEW_CHILD_JOB_ASSIGN_FAILED");
                    DateTime created = OwnedProcessNative.CreationTime(child);
                    if (OwnedProcessNative.ResumeThread(thread) == UInt32.MaxValue)
                        throw new InvalidOperationException("PREVIEW_CHILD_RESUME_FAILED");
                    var result = new OwnedChildProcess(child, checked((int)information.Id), created);
                    returned = true;
                    child = null; // Ownership transfers to the returned wrapper.
                    return result;
                } finally {
                    if (child != null) {
                        if (!returned) {
                            OwnedProcessNative.TerminateProcess(child, 1);
                            OwnedProcessNative.WaitForSingleObject(child, 5000);
                        }
                        child.Dispose();
                    }
                    if (thread != null) thread.Dispose();
                    if (attributesInitialized) OwnedProcessNative.DeleteProcThreadAttributeList(attributes);
                    if (attributes != IntPtr.Zero) Marshal.FreeHGlobal(attributes);
                    if (handleList != IntPtr.Zero) Marshal.FreeHGlobal(handleList);
                    if (input != null) input.Dispose();
                    if (output != null) output.Dispose();
                    if (error != null) error.Dispose();
                    OwnedProcessNative.ClearEnvironmentBlock(environmentBlock, environmentBytes);
                }
            }
        }

        public OwnedChildProcess ObserveListener(int pid) {
            lock (gate) {
                RequireJob();
                // Query identity/exit code and wait only; observing never grants termination access.
                var candidate = new OwnedKernelHandle(OwnedProcessNative.OpenProcess(0x101000, false, pid));
                try {
                    bool member;
                    if (pid <= 0 || candidate.IsInvalid ||
                            !OwnedProcessNative.IsProcessInJob(candidate, job, out member) || !member)
                        throw new InvalidOperationException("PREVIEW_LISTENER_NOT_OWNED");
                    var result = new OwnedChildProcess(candidate, pid, OwnedProcessNative.CreationTime(candidate));
                    candidate = null;
                    return result;
                } finally { if (candidate != null) candidate.Dispose(); }
            }
        }

        public bool Contains(int pid) {
            lock (gate) {
                RequireJob();
                if (pid <= 0) return false;
                using (var candidate = new OwnedKernelHandle(OwnedProcessNative.OpenProcess(0x1000, false, pid))) {
                    if (candidate.IsInvalid) return false;
                    bool member;
                    if (!OwnedProcessNative.IsProcessInJob(candidate, job, out member))
                        throw new InvalidOperationException("PREVIEW_CHILD_JOB_QUERY_FAILED");
                    return member;
                }
            }
        }

        static OwnedKernelHandle OpenFile(string path, uint access, uint sharing, uint disposition,
                ref OwnedProcessNative.SecurityAttributes security) {
            var handle = new OwnedKernelHandle(OwnedProcessNative.CreateFileW(path, access, sharing,
                ref security, disposition, 0x80, IntPtr.Zero));
            if (!handle.IsInvalid) return handle;
            handle.Dispose();
            throw new InvalidOperationException("PREVIEW_CHILD_STDIO_OPEN_FAILED");
        }

        void RequireJob() { if (job == null) throw new ObjectDisposedException("OwnedChildJob"); }

        public void Dispose() {
            lock (gate) {
                if (job != null) { job.Dispose(); job = null; }
            }
        }
    }

    internal sealed class OwnedKernelHandle : SafeHandleZeroOrMinusOneIsInvalid {
        internal OwnedKernelHandle(IntPtr value) : base(true) { SetHandle(value); }
        protected override bool ReleaseHandle() { return OwnedProcessNative.CloseHandle(handle); }
    }

    internal static class OwnedProcessNative {
        [StructLayout(LayoutKind.Sequential)] internal struct SecurityAttributes {
            internal int Length; internal IntPtr Descriptor;
            [MarshalAs(UnmanagedType.Bool)] internal bool InheritHandle;
        }
        [StructLayout(LayoutKind.Sequential)] internal struct StartupInfo {
            internal int Size; internal IntPtr Reserved, Desktop, Title;
            internal int X, Y, XSize, YSize, XCount, YCount, Fill, Flags;
            internal short Show, ReservedSize;
            internal IntPtr ReservedData, Input, Output, Error;
        }
        [StructLayout(LayoutKind.Sequential)] internal struct StartupInfoEx {
            internal StartupInfo Info; internal IntPtr Attributes;
        }
        [StructLayout(LayoutKind.Sequential)] internal struct ProcessInformation {
            internal IntPtr Process, Thread; internal uint Id, ThreadId;
        }
        [StructLayout(LayoutKind.Sequential)] internal struct BasicLimits {
            internal long ProcessTime, JobTime; internal uint LimitFlags;
            internal UIntPtr MinimumWorkingSet, MaximumWorkingSet;
            internal uint ActiveProcesses; internal UIntPtr Affinity;
            internal uint Priority, Scheduling;
        }
        [StructLayout(LayoutKind.Sequential)] internal struct IoCounters {
            internal ulong Reads, Writes, Others, ReadBytes, WriteBytes, OtherBytes;
        }
        [StructLayout(LayoutKind.Sequential)] internal struct ExtendedLimits {
            internal BasicLimits Basic; internal IoCounters Io;
            internal UIntPtr ProcessMemory, JobMemory, PeakProcessMemory, PeakJobMemory;
        }
        [StructLayout(LayoutKind.Sequential)] internal struct FileTime { internal uint Low, High; }

        [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        internal static extern IntPtr CreateJobObjectW(IntPtr security, string name);
        [DllImport("kernel32.dll", SetLastError = true)]
        internal static extern bool SetInformationJobObject(OwnedKernelHandle job, int kind, ref ExtendedLimits data, uint size);
        [DllImport("kernel32.dll", SetLastError = true)]
        internal static extern bool AssignProcessToJobObject(OwnedKernelHandle job, OwnedKernelHandle process);
        [DllImport("kernel32.dll", SetLastError = true)]
        internal static extern bool IsProcessInJob(OwnedKernelHandle process, OwnedKernelHandle job, out bool member);
        [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        internal static extern IntPtr CreateFileW(string path, uint access, uint sharing, ref SecurityAttributes security,
            uint disposition, uint flags, IntPtr template);
        [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        internal static extern bool CreateProcessW(string executable, StringBuilder command, IntPtr processSecurity,
            IntPtr threadSecurity, bool inheritHandles, uint flags, IntPtr environment, string directory,
            ref StartupInfoEx startup, out ProcessInformation information);
        [DllImport("kernel32.dll", SetLastError = true)]
        internal static extern bool InitializeProcThreadAttributeList(IntPtr attributes, int count, uint flags, ref IntPtr size);
        [DllImport("kernel32.dll", SetLastError = true)]
        internal static extern bool UpdateProcThreadAttribute(IntPtr attributes, uint flags, IntPtr attribute,
            IntPtr value, IntPtr size, IntPtr previous, IntPtr returnedSize);
        [DllImport("kernel32.dll")]
        internal static extern void DeleteProcThreadAttributeList(IntPtr attributes);
        [DllImport("kernel32.dll", SetLastError = true)]
        internal static extern uint ResumeThread(OwnedKernelHandle thread);
        [DllImport("kernel32.dll", SetLastError = true)]
        internal static extern bool TerminateProcess(OwnedKernelHandle process, uint code);
        [DllImport("kernel32.dll", SetLastError = true)]
        internal static extern uint WaitForSingleObject(OwnedKernelHandle process, uint milliseconds);
        [DllImport("kernel32.dll", SetLastError = true)]
        internal static extern bool GetExitCodeProcess(OwnedKernelHandle process, out uint code);
        [DllImport("kernel32.dll", SetLastError = true)]
        internal static extern IntPtr OpenProcess(uint access, bool inheritHandle, int pid);
        [DllImport("kernel32.dll", SetLastError = true)]
        internal static extern bool GetProcessTimes(OwnedKernelHandle process, out FileTime created, out FileTime exited,
            out FileTime kernel, out FileTime user);
        [DllImport("kernel32.dll")]
        internal static extern bool CloseHandle(IntPtr handle);

        internal static DateTime CreationTime(OwnedKernelHandle process) {
            FileTime created, exited, kernel, user;
            if (!GetProcessTimes(process, out created, out exited, out kernel, out user))
                throw new InvalidOperationException("PREVIEW_CHILD_IDENTITY_QUERY_FAILED");
            return DateTime.FromFileTimeUtc(((long)created.High << 32) | created.Low);
        }

        internal static IntPtr EnvironmentBlock(IDictionary environment, out int byteCount) {
            byteCount = 0;
            var sorted = new SortedDictionary<string, string>(StringComparer.OrdinalIgnoreCase);
            int length = 1;
            foreach (DictionaryEntry entry in environment) {
                string key = entry.Key as string, value = entry.Value as string;
                if (String.IsNullOrEmpty(key) || key.IndexOfAny(new char[] { '=', '\0' }) >= 0 ||
                        value == null || value.IndexOf('\0') >= 0 || sorted.ContainsKey(key))
                    throw new ArgumentException("PREVIEW_CHILD_ENVIRONMENT_INVALID");
                sorted.Add(key, value);
                length = checked(length + key.Length + value.Length + 2);
            }
            var block = new char[Math.Max(2, length)];
            IntPtr pointer = IntPtr.Zero;
            try {
                int offset = 0;
                foreach (var entry in sorted) {
                    entry.Key.CopyTo(0, block, offset, entry.Key.Length); offset += entry.Key.Length;
                    block[offset++] = '=';
                    entry.Value.CopyTo(0, block, offset, entry.Value.Length); offset += entry.Value.Length;
                    block[offset++] = '\0';
                }
                byteCount = checked(block.Length * 2);
                pointer = Marshal.AllocHGlobal(byteCount);
                Marshal.Copy(block, 0, pointer, block.Length);
                return pointer;
            } catch {
                ClearEnvironmentBlock(pointer, byteCount);
                throw;
            } finally { Array.Clear(block, 0, block.Length); }
        }

        internal static void ClearEnvironmentBlock(IntPtr pointer, int byteCount) {
            if (pointer == IntPtr.Zero) return;
            try { for (int i = 0; i < byteCount; i++) Marshal.WriteByte(pointer, i, 0); }
            finally { Marshal.FreeHGlobal(pointer); }
        }

        internal static string Quote(string value) {
            var result = new StringBuilder("\"");
            int slashes = 0;
            foreach (char character in value) {
                if (character == '\\') { slashes++; continue; }
                if (character == '"') {
                    result.Append('\\', slashes * 2 + 1); result.Append('"'); slashes = 0; continue;
                }
                result.Append('\\', slashes); slashes = 0; result.Append(character);
            }
            result.Append('\\', slashes * 2); result.Append('"');
            return result.ToString();
        }
    }
}
