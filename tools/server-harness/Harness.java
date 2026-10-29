import ru.ytkab0bp.slicebeam.server.LocalHttpServer;

import java.io.File;

public class Harness {
    public static void main(String[] args) throws Exception {
        File web = new File(args.length > 0 ? args[0] : "../../app/src/main/assets/web");
        File uploads = new File(args.length > 1 ? args[1] : "uploads");
        int port = args.length > 2 ? Integer.parseInt(args[2]) : 18081;
        LocalHttpServer server = new LocalHttpServer(port, new FakeBridge(), web, uploads, "test-token");
        System.out.println("listening on http://127.0.0.1:" + port + "/?t=test-token");
        System.out.println("web=" + web.getAbsolutePath());
        Thread.sleep(Long.MAX_VALUE);
    }
}
