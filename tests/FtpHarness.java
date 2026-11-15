import com.netpump.engineering.FtpRunner;
public class FtpHarness {
    public static void main(String[] args) throws Exception {
        FtpRunner.Config c = new FtpRunner.Config();
        c.host = "127.0.0.1"; c.port = Integer.parseInt(args[0]);
        c.user = "test"; c.password = "pass"; c.remotePath = "/download/20GB.ZIP";
        c.seconds = 2; c.endSec = 0; c.threads = 3;
        System.out.println(FtpRunner.probe(c));
        FtpRunner.Result r = new FtpRunner(c, s -> {
            System.out.printf("instant %.1f Mbps; bytes %d; active %d%n", s.currentMbps, s.bytes, s.active);
        }).run();
        System.out.println("Result bytes " + r.bytes + " avg " + r.averageMbps + " peak " + r.peakMbps + " error: " + r.error);
        if (r.bytes < 10000 || !r.error.isEmpty()) System.exit(2);
    }
}
