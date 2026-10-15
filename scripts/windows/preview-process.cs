using System;
using System.ComponentModel;
using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Text;
using System.IO;
using Microsoft.Win32.SafeHandles;

namespace Pingche.Preview {
    public static class Native {
        [DllImport("kernel32.dll")] static extern IntPtr GetCurrentProcess();
        [DllImport("kernel32.dll", SetLastError=true)] static extern bool IsProcessInJob(IntPtr process,IntPtr job,out bool member);
        [DllImport("kernel32.dll", SetLastError=true)] static extern bool QueryInformationJobObject(IntPtr job,int kind,IntPtr data,uint size,out uint returned);
        [DllImport("kernel32.dll", CharSet=CharSet.Unicode,SetLastError=true)] static extern uint GetFinalPathNameByHandleW(SafeFileHandle file,StringBuilder path,uint size,uint flags);
        public static string FinalToolPath(string path) {
            using(var file=File.Open(path,FileMode.Open,FileAccess.Read,FileShare.ReadWrite|FileShare.Delete)) {
                var result=new StringBuilder(32768); uint count=GetFinalPathNameByHandleW(file.SafeFileHandle,result,(uint)result.Capacity,0);
                if(count==0 || count>=result.Capacity) throw new InvalidOperationException("PREVIEW_TOOL_PATH_INVALID");
                string value=result.ToString();
                if(value.StartsWith("\\\\?\\UNC\\",StringComparison.OrdinalIgnoreCase)) return "\\\\"+value.Substring(8);
                return value.StartsWith("\\\\?\\",StringComparison.Ordinal) ? value.Substring(4) : value;
            }
        }
        public static uint CurrentJobFlags() {
            if(!CurrentInAnyJob()) return 0;
            int size=IntPtr.Size==8?144:112; IntPtr data=Marshal.AllocHGlobal(size);
            try { uint returned; if(!QueryInformationJobObject(IntPtr.Zero,9,data,(uint)size,out returned)) throw new Win32Exception(Marshal.GetLastWin32Error()); return unchecked((uint)Marshal.ReadInt32(data,16)); }
            finally { Marshal.FreeHGlobal(data); }
        }
        public static bool CurrentInAnyJob() {
            bool member; if(!IsProcessInJob(GetCurrentProcess(),IntPtr.Zero,out member)) throw new Win32Exception(Marshal.GetLastWin32Error());
            return member;
        }
        public static bool InAnyJob(int id) {
            using(var process=Process.GetProcessById(id)) {
                bool member; if(!IsProcessInJob(process.Handle,IntPtr.Zero,out member)) throw new Win32Exception(Marshal.GetLastWin32Error());
                return member;
            }
        }
        public static string Quote(string value) {
            if(value==null) throw new ArgumentNullException("value");
            if(value.Length>0 && value.IndexOfAny(new char[]{' ','\t','\n','\r','"'})<0) return value;
            var result=new StringBuilder("\""); int slashes=0;
            foreach(char ch in value) {
                if(ch=='\\'){slashes++;continue;}
                if(ch=='"'){result.Append('\\',slashes*2+1);result.Append('"');slashes=0;continue;}
                result.Append('\\',slashes);slashes=0;result.Append(ch);
            }
            result.Append('\\',slashes*2);result.Append('"');return result.ToString();
        }
    }
}
