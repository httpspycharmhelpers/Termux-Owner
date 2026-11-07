package sysr;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class Owner {
    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("usage: Owner <cmd> [args...]");
            System.exit(2);
        }
        StringBuilder sel = new StringBuilder(args[0]);
        for (int i = 1; i < args.length; i++) {
            sel.append('\u0001').append(args[i]);
        }
        List<String> c = new ArrayList<String>();
        c.add("/system/bin/content");
        c.add("query");
        c.add("--uri");
        c.add("content://com.termux.api.owner");
        c.add("--where");
        c.add(sel.toString());
        ProcessBuilder pb = new ProcessBuilder(c);
        pb.redirectErrorStream(true);
        prep(pb);

        String last = "";
        int code = 1;
        boolean restarted = false;
        for (int attempt = 0; attempt < 30; attempt++) {
            Process p = pb.start();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            copy(p.getInputStream(), out);
            if (!p.waitFor(20, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                last = "timeout waiting for provider";
                code = 1;
            } else {
                code = p.exitValue();
                last = out.toString("UTF-8");
            }
            if (last.indexOf("Could not find provider") < 0
                    && last.indexOf("Error while accessing provider") < 0
                    && last.indexOf("binder haven't been received") < 0
                    && last.indexOf("inited=false") < 0) {
                System.out.print(last);
                System.exit(code);
            }
            if (!restarted
                    && (last.indexOf("Could not find provider") >= 0
                        || last.indexOf("Error while accessing provider") >= 0)) {
                forceStop();
                restarted = true;
                Thread.sleep(1500);
            } else {
                Thread.sleep(1000);
            }
        }
        System.out.print(last);
        System.exit(code == 0 ? 1 : code);
    }

    static void forceStop() {
        try {
            ProcessBuilder stop = new ProcessBuilder("/system/bin/am", "force-stop", "com.termux.api");
            stop.redirectErrorStream(true);
            prep(stop);
            Process sp = stop.start();
            copy(sp.getInputStream(), new ByteArrayOutputStream());
            sp.waitFor();
        } catch (Throwable ignored) {
        }
    }

    static void prep(ProcessBuilder pb) {
        pb.environment().remove("LD_PRELOAD");
        pb.environment().remove("LD_LIBRARY_PATH");
        pb.environment().put("ANDROID_ROOT", "/system");
        pb.environment().put("ANDROID_DATA", "/data");
        pb.environment().put("PATH", "/system/bin");
    }

    static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        out.flush();
    }
}
