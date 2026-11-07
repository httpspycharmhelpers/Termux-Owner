package sysr;

import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

public class Main {
    static final String SVC_PERM = "vivo_permission_service";
    static final String SVC_BACKUP = "vivo_backup_service";
    static final String DESC_PERM = "vivo.app.security.IVivoPermissionService";
    static final String DESC_BACKUP = "vivo.app.backup.IVivoBackupManager";
    static final int CODE_BACKUP = 2;
    static final int CODE_RESTORE = 3;

    public static void main(String[] args) throws Exception {
        // identity: shell (uid 2000). The Shizuku rish bootstrap is merged into
        // this dex, so sysr no longer needs the external rish script/dex.
        // Hand everything after "rish" straight to it, untouched stdio.
        if (args.length >= 1 && "rish".equals(args[0])) {
            String[] rest = new String[args.length - 1];
            System.arraycopy(args, 1, rest, 0, rest.length);
            Class<?> loader = Class.forName("rikka.shizuku.shell.ShizukuShellLoader");
            loader.getMethod("main", String[].class).invoke(null, (Object) rest);
            return;
        }
        System.setErr(System.out);
        System.out.println("[start] pid=" + new java.io.File("/proc/self").getCanonicalFile().getName());
        if (args.length >= 2 && "-c".equals(args[0])) {
            StringBuilder sb = new StringBuilder();
            for (int i = 1; i < args.length; i++) {
                if (i > 1) {
                    sb.append(' ');
                }
                sb.append(args[i]);
            }
            System.exit(run(sb.toString()));
        }
        if (args.length > 0) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < args.length; i++) {
                if (i > 0) {
                    sb.append(' ');
                }
                sb.append(args[i]);
            }
            System.exit(run(sb.toString()));
        }

        BufferedReader br = new BufferedReader(new InputStreamReader(System.in));
        String line;
        int rc = 0;
        while ((line = br.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int r = run(line);
            if (r != 0) {
                rc = r;
            }
        }
        System.exit(rc);
    }

    static int run(String line) {
        try {
            String[] t = tok(line);
            if (t.length == 0) {
                return 0;
            }
            String cmd = t[0];
            if (cmd.equals("help")) {
                help();
                return 0;
            }
            if (cmd.equals("id")) {
                System.out.println("uid=" + Binder.getCallingUid() + " pid=" + Binder.getCallingPid());
                return 0;
            }
            if (cmd.equals("owner")) {
                if (t.length < 2) {
                    System.err.println("usage: owner <cmd> [args...]");
                    return 2;
                }
                return owner(t);
            }
            if (cmd.equals("svc")) {
                if (t.length < 2) {
                    System.err.println("usage: svc <name>");
                    return 2;
                }
                IBinder b = svc(t[1]);
                if (b == null) {
                    System.out.println("NOT_FOUND " + t[1]);
                    return 1;
                }
                System.out.println("FOUND " + t[1] + " desc=" + desc(b));
                return 0;
            }
            if (cmd.equals("binder")) {
                if (t.length < 3) {
                    System.err.println("usage: binder <svc> <code> [args...] [r:i32|r:bool|r:str|r:hex]");
                    return 2;
                }
                return binderCall(t[1], Integer.parseInt(t[2]), t, 3);
            }
            if (cmd.equals("vpm")) {
                if (t.length < 2) {
                    System.err.println("usage: vpm <code> [args...] [r:...]");
                    return 2;
                }
                String[] nt = new String[t.length + 1];
                nt[0] = "binder";
                nt[1] = SVC_PERM;
                System.arraycopy(t, 1, nt, 2, t.length - 1);
                return binderCall(SVC_PERM, Integer.parseInt(t[1]), nt, 3);
            }
            if (cmd.equals("perm-check")) {
                if (t.length < 3) {
                    System.err.println("usage: perm-check <pkg> <permission> [uid]");
                    return 2;
                }
                String uid = t.length > 3 ? t[3] : "0";
                return permCheck(t[1], t[2], Integer.parseInt(uid));
            }
            if (cmd.equals("perm-set")) {
                if (t.length < 5) {
                    System.err.println("usage: perm-set <pkg> <permission> <uid> <0|1>");
                    return 2;
                }
                return permSet(t[1], t[2], Integer.parseInt(t[3]), "1".equals(t[4]) || "true".equals(t[4]));
            }
            if (cmd.equals("svclist")) {
                Class<?> sm = Class.forName("android.os.ServiceManager");
                String[] names = null;
                try {
                    Object arr = sm.getMethod("getServiceDebugInfo").invoke(null);
                    if (arr instanceof Object[]) {
                        Object[] items = (Object[]) arr;
                        names = new String[items.length];
                        for (int i = 0; i < items.length; i++) {
                            java.lang.reflect.Field f = items[i].getClass().getDeclaredField("name");
                            f.setAccessible(true);
                            names[i] = String.valueOf(f.get(items[i]));
                        }
                    }
                } catch (Throwable ignore) {
                }
                if (names == null) {
                    System.out.println("no name-list method found; ServiceManager declares:");
                    for (java.lang.reflect.Method m : sm.getDeclaredMethods()) {
                        System.out.println("  " + m);
                    }
                    return 3;
                }
                java.util.Arrays.sort(names);
                System.out.println("services: " + names.length);
                for (String n : names) {
                    System.out.println(n);
                }
                return 0;
            }
            if (cmd.equals("sc")) {
                // sysr sc "<shell command>"        -> auto-pick the service
                // sysr sc <svc> "<shell command>"  -> explicit service
                if (t.length < 2) {
                    System.err.println("usage: sc \"<cmd>\"   |   sc <svc> \"<cmd>\"");
                    return 2;
                }
                if (t.length == 2) {
                    String c = t[1].trim();
                    int sp = c.indexOf(' ');
                    String svc = sp < 0 ? "" : c.substring(0, sp);
                    String rest = sp < 0 ? c : c.substring(sp + 1);
                    String[] guess = guessService(svc);
                    for (String cand : guess) {
                        if (svc(cand) != null) {
                            return shellCmd(cand, rest);
                        }
                    }
                    System.err.println("no service matches '" + svc
                            + "'. try: sc <svc> \"cmd\" | sysr svclist");
                    return 1;
                }
                return shellCmd(t[1], t[2]);
            }
            if (cmd.equals("asc")) {
                // ActivityManager.startShellCommand: same as `cmd <service> <args>`
                // but the service's output comes back as a real String.
                if (t.length < 3) {
                    System.err.println("usage: asc <svc> \"<command string>\"");
                    return 2;
                }
                return activityShellCmd(t[1], t[2]);
            }
            if (cmd.equals("vperm")) {
                if (t.length < 4) {
                    System.err.println("usage: vperm <pkg> <0|1> <id,id,..>");
                    return 2;
                }
                return permGroupSet(t[1], Integer.parseInt(t[2]), t[3]);
            }
            if (cmd.equals("backup")) {
                if (t.length < 3) {
                    System.err.println("usage: backup <pkg> <outfile> [flags|full|sys|kv|0,0,0,0,0,1,1,1]");
                    return 2;
                }
                return backup(t[1], t[2], parseFlags(t.length > 3 ? t[3] : ""));
            }
            if (cmd.equals("restore")) {
                if (t.length < 3) {
                    System.err.println("usage: restore <pkg> <infile> [path1,path2,...]");
                    return 2;
                }
                String[] paths = t.length > 3 ? t[3].split(",") : new String[0];
                return restore(t[1], t[2], paths);
            }
            if (cmd.equals("zipbackup")) {
                if (t.length < 3) {
                    System.err.println("usage: zipbackup <pkg> <out.zip> [dir1,dir2,...]");
                    return 2;
                }
                return backupZip(t[1], t[2], t.length > 3 ? t[3] : "");
            }
            if (cmd.equals("ziprestore") || cmd.equals("ziprestore-nc")) {
                if (t.length < 3) {
                    System.err.println("usage: " + cmd + " <pkg> <in.zip>");
                    return 2;
                }
                return restoreZip(t[1], t[2], cmd.equals("ziprestore") ? 5 : 25);
            }
            System.err.println("unknown command: " + cmd + " (try help)");
            return 2;
        } catch (Throwable e) {
            System.err.println("error: " + e);
            e.printStackTrace();
            return 3;
        }
    }

    static int permCheck(String pkg, String perm, int uid) throws Exception {
        IBinder b = svc(SVC_PERM);
        if (b == null) {
            return 1;
        }
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESC_PERM);
            data.writeString(pkg);
            data.writeString(perm);
            data.writeInt(uid);
            b.transact(15, data, reply, 0);
            reply.readException();
            System.out.println("checkOnePermission=" + reply.readInt() + " pkg=" + pkg + " perm=" + perm);
            return 0;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    static int permSet(String pkg, String perm, int uid, boolean granted) throws Exception {
        IBinder b = svc(SVC_PERM);
        if (b == null) {
            return 1;
        }
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESC_PERM);
            data.writeString(pkg);
            data.writeString(perm);
            data.writeInt(uid);
            data.writeBoolean(granted);
            b.transact(16, data, reply, 0);
            reply.readException();
            System.out.println("setOnePermission=" + reply.readBoolean() + " pkg=" + pkg + " perm=" + perm + " granted=" + granted);
            return 0;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    static int permGroupSet(String pkg, int result, String ids) throws Exception {
        IBinder b = svc(SVC_PERM);
        if (b == null) {
            System.err.println("service not found: " + SVC_PERM);
            return 1;
        }
        int ok = 0, fail = 0;
        for (String s : ids.split(",")) {
            s = s.trim();
            if (s.isEmpty()) continue;
            int id = Integer.parseInt(s);
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(DESC_PERM);
                data.writeString(pkg);
                data.writeInt(id);
                data.writeInt(result);
                b.transact(8, data, reply, 0);
                reply.readException();
                ok++;
            } catch (Throwable e) {
                System.err.println("vpTypeId " + id + " failed: " + e);
                fail++;
            } finally {
                reply.recycle();
                data.recycle();
            }
        }
        System.out.println("vperm pkg=" + pkg + " result=" + result + " ids=[" + ids + "] ok=" + ok + " fail=" + fail);
        return fail == 0 ? 0 : 4;
    }

    static int shellCmdTx() throws Exception {
        try {
            Class<?> c = Class.forName("android.os.IBinder");
            java.lang.reflect.Field f = c.getField("SHELL_COMMAND_TRANSACTION");
            return f.getInt(null);
        } catch (Throwable t) {
            return 0x5343484f; // FIRST_CALL_TRANSACTION + 'S','H','C','N'
        }
    }

    // map a `cmd <tool>`-style prefix onto the actual binder service name, so
    // `sysr sc "deviceidle whitelist"` works without the user knowing internals
    static String[] guessService(String tool) {
        String t = tool == null ? "" : tool.toLowerCase();
        if (t.isEmpty()) {
            return new String[]{"activity"};
        }
        if (t.startsWith("deviceidle") || t.startsWith("doze") || t.startsWith("idle")) {
            return new String[]{"deviceidle", "device_idle", "vivo_deviceidle"};
        }
        if (t.startsWith("appops") || t.startsWith("ops")) {
            return new String[]{"appops", "vivo_appops"};
        }
        if (t.startsWith("netpolicy") || t.startsWith("network")) {
            return new String[]{"netpolicy", "connectivity"};
        }
        if (t.startsWith("job") || t.startsWith("jobs")) {
            return new String[]{"jobscheduler", "vivo_job_scheduler"};
        }
        if (t.startsWith("device_config") || t.startsWith("deviceconfig")) {
            return new String[]{"device_config", "vivo_device_config"};
        }
        if (t.startsWith("overlay")) {
            return new String[]{"overlay", "vivo_overlay"};
        }
        if (t.startsWith("window")) {
            return new String[]{"window", "vivo_window"};
        }
        if (t.startsWith("input")) {
            return new String[]{"input", "input_manager"};
        }
        if (t.startsWith("power")) {
            return new String[]{"power", "vivo_power"};
        }
        if (t.startsWith("battery")) {
            return new String[]{"batterystats", "battery_stats", "power"};
        }
        if (t.startsWith("notification")) {
            return new String[]{"notification", "vivo_notification"};
        }
        if (t.startsWith("alarm")) {
            return new String[]{"alarm", "vivo_alarm"};
        }
        if (t.startsWith("role")) {
            return new String[]{"role", "vivo_role"};
        }
        if (t.startsWith("backup") || t.startsWith("bmgr")) {
            return new String[]{"backup", "vivo_backup_manager"};
        }
        if (t.startsWith("media") || t.startsWith("audio")) {
            return new String[]{"media.audio_policy", "audio_policy", "media_session"};
        }
        if (t.startsWith("camera")) {
            return new String[]{"camera", "vivo_camera"};
        }
        if (t.startsWith("telecom") || t.startsWith("telephony")) {
            return new String[]{"telecom", "telephony.registry", "vivo_telecom"};
        }
        if (t.startsWith("wifi")) {
            return new String[]{"wifi", "vivo_wifi"};
        }
        if (t.startsWith("bluetooth")) {
            return new String[]{"bluetooth_manager", "vivo_bt"};
        }
        if (t.startsWith("clipboard") || t.startsWith("clip")) {
            return new String[]{"clipboard", "vivo_clipboard"};
        }
        if (t.startsWith("vibrator")) {
            return new String[]{"vibrator_manager", "vibrator"};
        }
        if (t.startsWith("sensor")) {
            return new String[]{"sensor_service", "sensorservice", "vivo_sensor"};
        }
        if (t.startsWith("location")) {
            return new String[]{"location", "vivo_location"};
        }
        if (t.startsWith("connectivity")) {
            return new String[]{"connectivity", "vivo_network"};
        }
        if (t.startsWith("dpm") || t.startsWith("device_policy")) {
            return new String[]{"device_policy", "vivo_device_policy"};
        }
        if (t.startsWith("user") || t.startsWith("pm")) {
            return new String[]{"package", "user", "vivo_user"};
        }
        // not a known tool: maybe it already is a service name
        return new String[]{tool};
    }

    // IActivityManager.startShellCommand: the engine behind `cmd <service> <args>`.
    // Unlike IBinder.shellCommand (whose output goes to system_server's stdout,
    // i.e. /dev/null) this one hands the command output back as a real String.
    // IActivityManager.startShellCommand takes a ParceledListSlice<Integer> of
    // ShellCommandFlag.  There is no compile-time stub for it here, so build and
    // flatten one reflectively; a null slice is accepted as "no flags".
    static int activityShellCmd(String name, String cmdline) throws Exception {
        // The 3-arg startShellCommand() is what actually hands back the service's
        // text (IBinder.shellCommand cannot: that output goes to system_server's
        // stdout == /dev/null).  It lives on the *binder interface*
        // android.app.IActivityManager, not on android.app.ActivityManager -- the
        // latter only carries the 2-arg static helper, which returns void and
        // prints nowhere we can see.  So look it up on the interface and drive it
        // through the AIDL proxy, which knows its own transaction code (no
        // hardcoded code number that would rot on the next Android release).
        String[] parts = cmdline.trim().split("\\s+");
        String tool = parts.length > 0 ? parts[0] : name;
        String[] args = new String[Math.max(0, parts.length - 1)];
        for (int i = 1; i < parts.length; i++) {
            args[i - 1] = parts[i];
        }

        Class<?> iam = Class.forName("android.app.IActivityManager");
        java.lang.reflect.Method m = null;
        for (java.lang.reflect.Method cand : iam.getMethods()) {
            if (cand.getName().equals("startShellCommand") && cand.getParameterCount() == 3) {
                m = cand;
                break;
            }
        }
        if (m == null) {
            System.err.println("IActivityManager.startShellCommand not found");
            return 1;
        }
        Object proxy;
        try {
            IBinder b = (IBinder) iam.getMethod("getService").invoke(null);
            proxy = Class.forName("android.app.IActivityManager$Stub")
                         .getMethod("asInterface", IBinder.class).invoke(null, b);
        } catch (Throwable e) {
            System.err.println("IActivityManager proxy: " + e);
            return 1;
        }

        Object out;
        try {
            out = m.invoke(proxy, tool, args, null);
        } catch (java.lang.reflect.InvocationTargetException e) {
            System.err.println("startShellCommand: " + e.getCause());
            return 1;
        }
        // The reply is a hidden android.os.ParceledListSlice; read it reflectively
        // rather than importing a class that is not in the public SDK.
        if (out == null) {
            System.out.println("(no output)");
            return 0;
        }
        try {
            int n = ((Integer) out.getClass().getMethod("size").invoke(out)).intValue();
            java.lang.reflect.Method get = out.getClass().getMethod("get", int.class);
            for (int i = 0; i < n; i++) {
                Object line = get.invoke(out, Integer.valueOf(i));
                System.out.println(line == null ? "" : line.toString());
            }
            if (n == 0) {
                System.out.println("(no output)");
            }
        } catch (Throwable e) {
            System.out.println(out.toString());
        }
        return 0;
    }

    // run a service's shellCommand handler as system_server (this is the path
    // behind `cmd <service>`), i.e. with uid 1000 privileges
    static int shellCmd(String name, String cmdline) throws Exception {
        IBinder b = svc(name);
        if (b == null) {
            System.err.println("service not found: " + name);
            return 1;
        }
        int tx = shellCmdTx();
        String token = desc(b);
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(token);
            data.writeString(cmdline);
            if (!b.transact(tx, data, reply, 0)) {
                System.err.println("transact failed tx=" + tx + " token=" + token);
                return 3;
            }
            reply.readException();
            try {
                String out = reply.readString();
                if (out != null && !out.isEmpty()) {
                    System.out.println(out);
                    return 0;
                }
            } catch (Throwable ignore) {
            }
            byte[] raw = reply.marshall();
            String s = new String(raw, java.nio.charset.StandardCharsets.UTF_8);
            if (s.trim().isEmpty()) {
                System.out.println("(no string reply, avail=" + reply.dataAvail() + ") hex: " + hex(raw));
            } else {
                System.out.println(s);
            }
            return 0;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    static final boolean[] DEFAULT_BACKUP_FLAGS = {false, false, false, false, false, false, true, true};

    // spec: "" = defaults, "sys" = includeSystem, "full" = everything,
    // "0,0,0,0,0,1,1,1" = explicit (apk,obb,shared,widgets,allapps,system,compress,keyvalue)
    static boolean[] parseFlags(String spec) {
        boolean[] f = DEFAULT_BACKUP_FLAGS.clone();
        if (spec == null) return f;
        String s = spec.trim();
        if (s.isEmpty()) return f;
        if (s.equals("full")) {
            for (int i = 0; i < f.length; i++) f[i] = true;
            return f;
        }
        if (s.equals("sys")) {
            f[5] = true;
            return f;
        }
        if (s.equals("kv")) {
            f[5] = true;
            f[7] = true;
            return f;
        }
        String[] p = s.split(",");
        for (int i = 0; i < p.length && i < f.length; i++) {
            String t = p[i].trim();
            f[i] = t.equals("1") || t.equalsIgnoreCase("true") || t.equalsIgnoreCase("y");
        }
        return f;
    }

    static int backup(String pkg, String out, boolean[] flags) throws Exception {
        IBinder b = svc(SVC_BACKUP);
        if (b == null) {
            System.err.println("service not found: " + SVC_BACKUP);
            return 1;
        }
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(descOr(b, DESC_BACKUP));
            data.writeString(pkg);
            ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
            data.writeTypedObject(pipe[1], 0);
            data.writeStrongBinder(new Observer());
            for (int i = 0; i < DEFAULT_BACKUP_FLAGS.length; i++) {
                data.writeBoolean(flags[i]);
            }
            PipeJob job = new PipeJob("out", out, pipe[0], pipe[1]);
            Thread th = new Thread(job);
            th.setDaemon(true);
            th.start();
            boolean transacted = b.transact(CODE_BACKUP, data, reply, 0);
            reply.readException();
            boolean res = reply.readBoolean();
            try {
                pipe[1].close();
            } catch (Throwable ignore) {
            }
            th.join(180000);
            job.stopRequested = true;
            th.join(2000);
            System.out.println("backup pkg=" + pkg + " result=" + res + " transacted=" + transacted + " out=" + out);
            return res ? 0 : 4;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    static int backupZip(String pkg, String out, String dirsCsv) throws Exception {
        IBinder b = svc(SVC_BACKUP);
        if (b == null) {
            System.err.println("service not found: " + SVC_BACKUP);
            return 1;
        }
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(descOr(b, DESC_BACKUP));
            data.writeString(pkg);
            ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
            data.writeTypedObject(pipe[1], 0);
            data.writeBoolean(false);
            String[] dirs = dirsCsv == null || dirsCsv.isEmpty() ? new String[0] : dirsCsv.split(",");
            data.writeStringArray(dirs);
            data.writeStrongBinder(new Observer());
            PipeJob job = new PipeJob("out", out, pipe[0], pipe[1]);
            Thread th = new Thread(job);
            th.setDaemon(true);
            th.start();
            boolean transacted = b.transact(4, data, reply, 0);
            reply.readException();
            boolean res = reply.readBoolean();
            try {
                pipe[1].close();
            } catch (Throwable ignore) {
            }
            th.join(180000);
            job.stopRequested = true;
            th.join(2000);
            System.out.println("backupZip pkg=" + pkg + " result=" + res + " transacted=" + transacted + " out=" + out);
            return res ? 0 : 4;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    static int restoreZip(String pkg, String in, int code) throws Exception {
        IBinder b = svc(SVC_BACKUP);
        if (b == null) {
            System.err.println("service not found: " + SVC_BACKUP);
            return 1;
        }
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(descOr(b, DESC_BACKUP));
            data.writeString(pkg);
            ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
            data.writeTypedObject(pipe[0], 0);
            data.writeBoolean(false);
            data.writeStringArray(new String[0]);
            data.writeStrongBinder(new Observer());
            PipeJob job = new PipeJob("in", in, pipe[1], pipe[0]);
            Thread th = new Thread(job);
            th.setDaemon(true);
            th.start();
            boolean transacted = b.transact(code, data, reply, 0);
            reply.readException();
            boolean res = reply.readBoolean();
            try {
                pipe[1].close();
            } catch (Throwable ignore) {
            }
            job.stopRequested = true;
            th.join(5000);
            System.out.println("restoreZip(" + code + ") pkg=" + pkg + " result=" + res + " transacted=" + transacted + " in=" + in);
            return res ? 0 : 4;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    static int restore(String pkg, String in, String[] paths) throws Exception {
        IBinder b = svc(SVC_BACKUP);
        if (b == null) {
            System.err.println("service not found: " + SVC_BACKUP);
            return 1;
        }
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(descOr(b, DESC_BACKUP));
            data.writeString(pkg);
            ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
            data.writeTypedObject(pipe[0], 0);
            data.writeStringArray(paths);
            data.writeStrongBinder(new Observer());
            PipeJob job = new PipeJob("in", in, pipe[1], pipe[0]);
            Thread th = new Thread(job);
            th.setDaemon(true);
            th.start();
            boolean transacted = b.transact(CODE_RESTORE, data, reply, 0);
            reply.readException();
            boolean res = reply.readBoolean();
            job.stopRequested = true;
            th.join(5000);
            System.out.println("restore pkg=" + pkg + " result=" + res + " transacted=" + transacted + " in=" + in);
            return res ? 0 : 4;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    static int binderCall(String name, int code, String[] t, int argStart) throws Exception {
        IBinder b = svc(name);
        if (b == null) {
            System.err.println("service not found: " + name);
            return 1;
        }
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        List<Thread> jobs = new ArrayList<>();
        List<ParcelFileDescriptor[]> pipes = new ArrayList<>();
        String ret = "hex";
        try {
            data.writeInterfaceToken(descOr(b, null));
            int i = argStart;
            while (i < t.length) {
                String k = t[i++];
                if (k.startsWith("r:")) {
                    ret = k.substring(2);
                    continue;
                }
                if (k.equals("s16")) {
                    data.writeString(t[i++]);
                } else if (k.equals("i32")) {
                    data.writeInt(Integer.parseInt(t[i++]));
                } else if (k.equals("i64")) {
                    data.writeLong(Long.parseLong(t[i++]));
                } else if (k.equals("b")) {
                    data.writeBoolean(Boolean.parseBoolean(t[i++]));
                } else if (k.equals("null")) {
                    data.writeStrongBinder(null);
                } else if (k.equals("sarr")) {
                    data.writeStringArray(t[i].indexOf(',') >= 0 ? t[i++].split(",") : new String[]{t[i++]});
                } else if (k.equals("pfd-out")) {
                    String path = t[i++];
                    ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
                    pipes.add(pipe);
                    jobs.add(startThread(new PipeJob("out", path, pipe[0], pipe[1])));
                    data.writeTypedObject(pipe[1], 0);
                } else if (k.equals("pfd-in")) {
                    String path = t[i++];
                    ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
                    pipes.add(pipe);
                    jobs.add(startThread(new PipeJob("in", path, pipe[1], pipe[0])));
                    data.writeTypedObject(pipe[0], 0);
                } else {
                    System.err.println("bad arg: " + k);
                    return 2;
                }
            }
            b.transact(code, data, reply, 0);
            reply.readException();
            if (ret.equals("i32")) {
                System.out.println("Result: " + reply.readInt());
            } else if (ret.equals("i64")) {
                System.out.println("Result: " + reply.readLong());
            } else if (ret.equals("bool")) {
                System.out.println("Result: " + reply.readBoolean());
            } else if (ret.equals("str")) {
                System.out.println("Result: " + reply.readString());
            } else if (ret.equals("strlist")) {
                int n = reply.readInt();
                StringBuilder sb = new StringBuilder();
                for (int j = 0; j < n; j++) {
                    String v = reply.readString();
                    if (v == null) continue;
                    if (sb.length() > 0) sb.append("\n");
                    sb.append(v);
                }
                System.out.println("Result[" + n + "]:");
                System.out.println(sb);
            } else if (ret.equals("obj") || ret.equals("typed") || ret.equals("parcel") || ret.equals("vpi")) {
                try {
                    System.out.println("Result: " + hex(reply.marshall()));
                } catch (Throwable e) {
                    System.out.println("Result: (unreadable) avail=" + reply.dataAvail());
                }
            } else if (ret.equals("none")) {
                System.out.println("Result: (empty) avail=" + reply.dataAvail());
            } else {
                System.out.println("Result: " + hex(reply.marshall()));
            }
            for (ParcelFileDescriptor[] p : pipes) {
                try {
                    p[1].close();
                } catch (Throwable ignore) {
                }
            }
            for (Thread th : jobs) {
                th.join(60000);
            }
            return 0;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    static Thread startThread(Runnable r) {
        Thread th = new Thread(r);
        th.setDaemon(true);
        th.start();
        return th;
    }

    static class PipeJob implements Runnable {
        final String mode;
        final String path;
        final ParcelFileDescriptor ourEnd;
        final ParcelFileDescriptor theirEnd;
        volatile boolean stopRequested = false;

        PipeJob(String mode, String path, ParcelFileDescriptor ourEnd, ParcelFileDescriptor theirEnd) {
            this.mode = mode;
            this.path = path;
            this.ourEnd = ourEnd;
            this.theirEnd = theirEnd;
        }

        @Override
        public void run() {
            try {
                if ("out".equals(mode)) {
                    InputStream in = new FileInputStream(ourEnd.getFileDescriptor());
                    OutputStream out = new FileOutputStream(path);
                    byte[] buf = new byte[8192];
                    long lastData = System.currentTimeMillis();
                    while (true) {
                        int avail = in.available();
                        if (avail > 0) {
                            int n = in.read(buf, 0, Math.min(buf.length, avail));
                            if (n <= 0) {
                                break;
                            }
                            out.write(buf, 0, n);
                            lastData = System.currentTimeMillis();
                            continue;
                        }
                        long now = System.currentTimeMillis();
                        if (now - lastData > 120000) {
                            System.out.println("pipe read timeout");
                            break;
                        }
                        if (stopRequested && now - lastData > 1000) {
                            break;
                        }
                        Thread.sleep(50);
                    }
                    out.flush();
                    out.close();
                    in.close();
                } else {
                    InputStream in = new FileInputStream(path);
                    OutputStream out = new FileOutputStream(ourEnd.getFileDescriptor());
                    byte[] buf = new byte[8192];
                    int n;
                    while (!stopRequested && (n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                    }
                    out.flush();
                    out.close();
                    in.close();
                }
                ourEnd.close();
            } catch (Throwable e) {
                System.out.println("pipe " + mode + " " + path + " failed: " + e);
            }
        }
    }

    static int owner(String[] t) {
        List<String> c = new ArrayList<>();
        c.add("/system/bin/content");
        c.add("query");
        c.add("--uri");
        c.add("content://com.termux.api.owner");
        c.add("--where");
        c.add(t[1]);
        for (int i = 2; i < t.length; i++) {
            c.add("--arg");
            c.add(t[i]);
        }
        ProcessBuilder pb = new ProcessBuilder(c);
        pb.redirectErrorStream(true);
        pb.environment().remove("LD_PRELOAD");
        pb.environment().remove("LD_LIBRARY_PATH");
        pb.environment().put("ANDROID_ROOT", "/system");
        pb.environment().put("ANDROID_DATA", "/data");
        pb.environment().put("PATH", "/system/bin");
        try {
            Process p = pb.start();
            copy(p.getInputStream(), System.out);
            return p.waitFor();
        } catch (Throwable e) {
            System.err.println("owner: " + e);
            return 1;
        }
    }

    static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        out.flush();
    }

    static IBinder svc(String name) throws Exception {
        Class<?> sm = Class.forName("android.os.ServiceManager");
        return (IBinder) sm.getMethod("getService", String.class).invoke(null, name);
    }

    static String desc(IBinder b) {
        try {
            Object d = b.getClass().getMethod("getInterfaceDescriptor").invoke(b);
            return d == null ? "?" : d.toString();
        } catch (Throwable t) {
            return "?";
        }
    }

    static String descOr(IBinder b, String fallback) {
        String d = desc(b);
        if (d != null && !d.equals("?")) {
            return d;
        }
        return fallback == null ? "" : fallback;
    }

    static String hex(byte[] b) {
        if (b == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < b.length; i++) {
            sb.append(String.format("%02x", b[i]));
            if ((i & 3) == 3) {
                sb.append(' ');
            }
        }
        return sb.toString();
    }

    static String[] tok(String s) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean sq = false;
        boolean dq = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') {
                dq = !dq;
                continue;
            }
            if (c == '\'' && !dq) {
                sq = !sq;
                continue;
            }
            if (Character.isWhitespace(c) && !sq && !dq) {
                if (cur.length() > 0) {
                    out.add(cur.toString());
                    cur.setLength(0);
                }
                continue;
            }
            cur.append(c);
        }
        if (cur.length() > 0) {
            out.add(cur.toString());
        }
        return out.toArray(new String[0]);
    }

    static void help() {
        System.out.println("sysr - vivo system service client (shell uid 2000)");
        System.out.println();
        System.out.println("  id                          show effective uid/pid");
        System.out.println("  owner <cmd> [args...]       send command to com.termux.api owner bridge");
        System.out.println("  svc <name>                  look up a ServiceManager service + descriptor");
        System.out.println("  svclist                     list every ServiceManager service name");
        System.out.println("  perm-check <pkg> <perm> [uid]      -> vivo_permission_service 15");
        System.out.println("  perm-set <pkg> <perm> <uid> <0|1>  -> vivo_permission_service 16");
        System.out.println("  vperm <pkg> <0|1> <id,id,..>           -> vivo_permission_service 8, batch");
        System.out.println("  backup <pkg> <outfile>      dump app private data via vivo_backup_service");
        System.out.println("  restore <pkg> <infile> [path,path..]  push data into app private dir");
        System.out.println("  vpm <code> [args...] [r:i32|r:bool|r:str|r:hex]");
        System.out.println("  sc <svc> <command>          -> service shellCommand as system_server (like `cmd`)");
        System.out.println("  zipbackup <pkg> <out.zip>   -> vivo backup by zip (transaction 4)");
        System.out.println("  ziprestore <pkg> <in.zip>   -> restore by zip (5), -nc = no-clear (25)");
        System.out.println("  binder <svc> <code> [args...] [r:...]");
        System.out.println();
        System.out.println("  args: s16 <str>  i32 <n>  i64 <n>  b <true|false>  null  sarr a,b,c");
        System.out.println("        pfd-in <file>   give service a read end, we feed the file");
        System.out.println("        pfd-out <file>  give service a write end, we capture to file");
    }
}
