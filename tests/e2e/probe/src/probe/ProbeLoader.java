package probe;

import com.sun.tools.attach.VirtualMachine;

/** Attach a probe agent jar with agent arguments: ProbeLoader <pid> <jar> [args]. */
public class ProbeLoader {
    public static void main(String[] a) throws Exception {
        VirtualMachine vm = VirtualMachine.attach(a[0]);
        try {
            long t = System.currentTimeMillis();
            if (a.length > 2 && !a[2].isEmpty()) vm.loadAgent(a[1], a[2]);
            else vm.loadAgent(a[1]);
            System.out.println("attached " + a[1] + " args=" + (a.length > 2 ? a[2] : "")
                    + " startEpochMs=" + t + " endEpochMs=" + System.currentTimeMillis());
        } finally { vm.detach(); }
    }
}
