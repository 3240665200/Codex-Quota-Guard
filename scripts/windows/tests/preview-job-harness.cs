using System;
using System.ComponentModel;
using System.Runtime.InteropServices;
using System.Text;

// Synthetic tests only: every terminated handle was created by this fixture.
public sealed class PreviewSyntheticJob : IDisposable {
    [StructLayout(LayoutKind.Sequential)] struct SI {
        public int cb; public IntPtr reserved, desktop, title; public int x,y,xs,ys,xc,yc,fill,flags;
        public short show, reserved2; public IntPtr extra, input, output, error;
    }
    [StructLayout(LayoutKind.Sequential)] struct PI { public IntPtr process, thread; public uint pid, tid; }
    [DllImport("kernel32.dll", CharSet=CharSet.Unicode, SetLastError=true)] static extern bool CreateProcessW(string exe,StringBuilder command,IntPtr pa,IntPtr ta,bool inherit,uint flags,IntPtr env,string cwd,ref SI si,out PI pi);
    [DllImport("kernel32.dll", CharSet=CharSet.Unicode, SetLastError=true)] static extern IntPtr CreateJobObjectW(IntPtr security,string name);
    [DllImport("kernel32.dll", SetLastError=true)] static extern bool SetInformationJobObject(IntPtr job,int kind,IntPtr data,uint size);
    [DllImport("kernel32.dll", SetLastError=true)] static extern bool AssignProcessToJobObject(IntPtr job,IntPtr process);
    [DllImport("kernel32.dll", SetLastError=true)] static extern bool IsProcessInJob(IntPtr process,IntPtr job,out bool member);
    [DllImport("kernel32.dll", SetLastError=true)] static extern uint ResumeThread(IntPtr thread);
    [DllImport("kernel32.dll")] static extern bool CloseHandle(IntPtr handle);
    [DllImport("kernel32.dll")] static extern bool TerminateProcess(IntPtr process,uint code);
    [DllImport("kernel32.dll")] static extern uint WaitForSingleObject(IntPtr handle,uint timeout);
    IntPtr outer, inner, process;
    public uint ProcessId { get; private set; }
    public static PreviewSyntheticJob Start(string exe,string arguments,string cwd,bool allowBreakaway,bool nestedDeny) {
        var owned=new PreviewSyntheticJob(); var si=new SI(); si.cb=Marshal.SizeOf(typeof(SI)); PI pi;
        // Escape the tool's Job before entering only the newly created test Job(s).
        if(!CreateProcessW(exe,new StringBuilder("\""+exe+"\" "+arguments),IntPtr.Zero,IntPtr.Zero,false,0x01000000|0x08000000|4,IntPtr.Zero,cwd,ref si,out pi)) throw new Win32Exception(Marshal.GetLastWin32Error());
        owned.process=pi.process; owned.ProcessId=pi.pid;
        try {
            bool inherited;
            if(!IsProcessInJob(pi.process,IntPtr.Zero,out inherited)) throw new InvalidOperationException("Synthetic controller Job query failed");
            owned.outer=NewJob(nestedDeny?false:allowBreakaway);
            if(!AssignProcessToJobObject(owned.outer,pi.process)) throw new Win32Exception(Marshal.GetLastWin32Error());
            if(nestedDeny) { owned.inner=NewJob(true); if(!AssignProcessToJobObject(owned.inner,pi.process)) throw new Win32Exception(Marshal.GetLastWin32Error()); }
            if(ResumeThread(pi.thread)==0xffffffff) throw new Win32Exception(Marshal.GetLastWin32Error());
            return owned;
        } catch { owned.Dispose(); throw; }
        finally { CloseHandle(pi.thread); }
    }
    static IntPtr NewJob(bool breakaway) {
        IntPtr job=CreateJobObjectW(IntPtr.Zero,null); if(job==IntPtr.Zero) throw new Win32Exception(Marshal.GetLastWin32Error());
        int size=IntPtr.Size==8?144:112; IntPtr data=Marshal.AllocHGlobal(size);
        try {
            for(int i=0;i<size;i++) Marshal.WriteByte(data,i,0);
            Marshal.WriteInt32(data,16,0x2000|(breakaway?0x800:0));
            if(!SetInformationJobObject(job,9,data,(uint)size)) throw new Win32Exception(Marshal.GetLastWin32Error());
            return job;
        } catch { CloseHandle(job); throw; } finally { Marshal.FreeHGlobal(data); }
    }
    public void CloseJob() { if(inner!=IntPtr.Zero){CloseHandle(inner);inner=IntPtr.Zero;} if(outer!=IntPtr.Zero){CloseHandle(outer);outer=IntPtr.Zero;} }
    public void Dispose() { CloseJob(); if(process!=IntPtr.Zero){ if(WaitForSingleObject(process,5000)!=0){TerminateProcess(process,99);WaitForSingleObject(process,5000);} CloseHandle(process);process=IntPtr.Zero;} }
}
