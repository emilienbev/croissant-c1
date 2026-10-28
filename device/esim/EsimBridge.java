import android.os.Binder;
import android.os.IBinder;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Root bridge from a line protocol on stdin/stdout to the SIM's logical channels,
 * through the phone service (ITelephony). Runs as uid 0:
 *
 *   su -c "CLASSPATH=/data/local/tmp/esim-bridge.dex app_process /system/bin EsimBridge"
 *
 * Protocol, one request per line, one answer per line:
 *   open <aid hex>        -> ok <channel>      | err <message>
 *   tx <channel> <apdu>   -> ok <response hex> | err <message>   (response ends with SW1 SW2)
 *   close <channel>       -> ok                | err <message>
 *   simpower <0|1>        -> ok                | err <message>   (0 powers the SIM down, 1 up)
 * "ready" is printed once at start. End of input closes every open channel and exits.
 *
 * The AID is sent in lowercase: the phone process refuses the ISD-R AID to anyone
 * but the registered LPA with a case-sensitive compare, and the modem does not care.
 * Each channel carries a Binder, so the phone process closes it if this process dies.
 */
public class EsimBridge {
    private static final int SLOT = 0;
    private static final int PORT = 0;

    private static Object tel;
    private static final Map<Integer, Object> open = new HashMap<>();

    public static void main(String[] args) throws Exception {
        PrintStream out = new PrintStream(System.out, true);
        IBinder b = (IBinder) Class.forName("android.os.ServiceManager")
            .getMethod("getService", String.class).invoke(null, "phone");
        tel = Class.forName("com.android.internal.telephony.ITelephony$Stub")
            .getMethod("asInterface", IBinder.class).invoke(null, b);
        out.println("ready");

        BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
        String line;
        try {
            while ((line = in.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                try {
                    out.println(handle(line.split(" ")));
                } catch (Throwable t) {
                    Throwable c = t.getCause() != null ? t.getCause() : t;
                    out.println("err " + c.getClass().getSimpleName() + ": " + c.getMessage());
                }
            }
        } finally {
            for (Object req : open.values()) {
                try { method("iccCloseLogicalChannel", 1).invoke(tel, req); } catch (Throwable ignored) { }
            }
        }
    }

    private static String handle(String[] p) throws Exception {
        switch (p[0]) {
            case "open": {
                Object req = request(p[1].toLowerCase(Locale.ROOT));
                Object resp = method("iccOpenLogicalChannel", 1).invoke(tel, req);
                int channel = (Integer) resp.getClass().getMethod("getChannel").invoke(resp);
                int status = (Integer) resp.getClass().getMethod("getStatus").invoke(resp);
                if (status != 1 || channel <= 0) return "err open status " + status;
                field(req, "channel").set(req, channel);
                open.put(channel, req);
                return "ok " + channel;
            }
            case "tx": {
                int channel = Integer.parseInt(p[1]);
                String apdu = p[2];
                int cla = Integer.parseInt(apdu.substring(0, 2), 16);
                int ins = Integer.parseInt(apdu.substring(2, 4), 16);
                int p1 = Integer.parseInt(apdu.substring(4, 6), 16);
                int p2 = Integer.parseInt(apdu.substring(6, 8), 16);
                int p3 = apdu.length() >= 10 ? Integer.parseInt(apdu.substring(8, 10), 16) : -1;
                String data = apdu.length() > 10 ? apdu.substring(10) : "";
                String r = (String) method("iccTransmitApduLogicalChannelByPort", 9)
                    .invoke(tel, SLOT, PORT, channel, cla, ins, p1, p2, p3, data);
                if (r == null || r.length() < 4) return "err no response";
                return "ok " + r.toUpperCase(Locale.ROOT);
            }
            case "close": {
                Object req = open.remove(Integer.parseInt(p[1]));
                if (req == null) return "err not open";
                method("iccCloseLogicalChannel", 1).invoke(tel, req);
                return "ok";
            }
            case "simpower": {
                // The removable eUICC does not make the modem reload after a switch,
                // so the LPA powers the SIM down and up itself.
                method("setSimPowerStateForSlot", 2).invoke(tel, SLOT, Integer.parseInt(p[1]));
                return "ok";
            }
            default:
                return "err unknown " + p[0];
        }
    }

    private static Object request(String aid) throws Exception {
        Object r = Class.forName("com.android.internal.telephony.IccLogicalChannelRequest")
            .getConstructor().newInstance();
        field(r, "slotIndex").set(r, SLOT);
        field(r, "portIndex").set(r, PORT);
        field(r, "subId").set(r, -1);
        field(r, "callingPackage").set(r, "root");
        field(r, "aid").set(r, aid);
        field(r, "p2").set(r, 0);
        field(r, "binder").set(r, new Binder());
        return r;
    }

    private static Field field(Object o, String name) throws Exception {
        return o.getClass().getField(name);
    }

    private static Method method(String name, int argc) {
        for (Method m : tel.getClass().getMethods())
            if (m.getName().equals(name) && m.getParameterCount() == argc) return m;
        throw new IllegalStateException("no " + name + "/" + argc);
    }
}
