package sysr;

import android.os.Binder;
import android.os.Parcel;
import android.os.RemoteException;

public class Observer extends Binder {
    public static final String DESC = "vivo.app.backup.IPackageBackupRestoreObserver";

    private static final int ON_START = 1;
    private static final int ON_END = 2;
    private static final int ON_PROGRESS = 3;
    private static final int ON_ERROR = 4;

    public Observer() {
        attachInterface(null, DESC);
    }

    @Override
    protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        try {
            data.enforceInterface(DESC);
        } catch (Throwable ignore) {
        }
        switch (code) {
            case ON_START: {
                String pkg = data.readString();
                int feature = data.readInt();
                log("start pkg=" + pkg + " feature=" + feature);
                break;
            }
            case ON_END: {
                String pkg = data.readString();
                int feature = data.readInt();
                log("end pkg=" + pkg + " feature=" + feature);
                break;
            }
            case ON_PROGRESS: {
                String pkg = data.readString();
                int feature = data.readInt();
                long done = data.readLong();
                long total = data.readLong();
                log("progress pkg=" + pkg + " " + done + "/" + total);
                break;
            }
            case ON_ERROR: {
                String pkg = data.readString();
                int feature = data.readInt();
                int errno = data.readInt();
                log("error pkg=" + pkg + " feature=" + feature + " errno=" + errno);
                break;
            }
            default:
                return super.onTransact(code, data, reply, flags);
        }
        if (reply != null) {
            reply.writeNoException();
        }
        return true;
    }

    private static void log(String msg) {
        System.err.println("[observer] " + msg);
    }
}
