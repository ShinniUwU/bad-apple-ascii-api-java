import com.sun.net.httpserver.HttpServer;
import org.jcodec.api.FrameGrab;
import org.jcodec.api.JCodecException;
import org.jcodec.common.io.NIOUtils;
import org.jcodec.common.model.Picture;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.*;

@Path("/play")
public class Main {
    private static final String VIDEO_PATH = "src/main/resources/bad_apple.mp4";
    private static final int DEFAULT_WIDTH = 80;
    private static final int FRAME_SWITCH_INTERVAL_MS = 1000;

    private static int currentFrame = 1;
    private static HttpServer server;
    private static ExecutorService executorService = Executors.newFixedThreadPool(5);

    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public Response play() {
        return playFrame(currentFrame);
    }

    @GET
    @Path("/{frameNumber}")
    @Produces(MediaType.TEXT_PLAIN)
    public Response play(@PathParam("frameNumber") int frameNumber) {
        return playFrame(frameNumber);
    }

    private static Response playFrame(int frameNumber) {
        try {
            String frame = getFrameAsciiArt(frameNumber);
            return Response.ok(frame).build();
        } catch (Exception e) {
            e.printStackTrace();
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity("Error processing frame").build();
        }
    }

    public static void main(String[] args) {
        try {
            server = HttpServer.create(new InetSocketAddress(8080), 0);
            server.createContext("/play", new PlayAPIHandler());
            server.setExecutor(null);
            server.start();
            System.out.println("Server is running on http://localhost:8080");

            Timer timer = new Timer();
            timer.scheduleAtFixedRate(new FrameSwitchTask(), 0, FRAME_SWITCH_INTERVAL_MS);

            System.out.println("Frames will switch automatically. Press Enter to stop.");
            System.in.read();
            server.stop(0);
            timer.cancel();
            executorService.shutdown();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static String getFrameAsciiArt(int frameNumber) {
        try (var channel = NIOUtils.readableChannel(new File(VIDEO_PATH))) {
            FrameGrab grab = FrameGrab.createFrameGrab(channel);
            grab.seekToFramePrecise(frameNumber);
            Picture picture = grab.getNativeFrame();
            BufferedImage bufferedImage = convertToBufferedImage(picture);
            return convertToAscii(bufferedImage);
        } catch (IOException | JCodecException e) {
            e.printStackTrace();
            return "Error processing frame";
        }
    }

    private static BufferedImage convertToBufferedImage(Picture picture) {
        BufferedImage image = new BufferedImage(picture.getWidth(), picture.getHeight(), BufferedImage.TYPE_3BYTE_BGR);
        for (int y = 0; y < picture.getHeight(); y++) {
            for (int x = 0; x < picture.getWidth(); x++) {
                int rgb = picture.getPlaneData(0)[y * picture.getWidth() + x] & 0xff;
                rgb = (rgb << 16) | (rgb << 8) | rgb;
                image.setRGB(x, y, rgb);
            }
        }
        return image;
    }

    private static String convertToAscii(BufferedImage image) {
        int width = DEFAULT_WIDTH;
        int height = (int) (image.getHeight() * ((double) DEFAULT_WIDTH / image.getWidth()));
        BufferedImage scaledImage = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g2d = scaledImage.createGraphics();
        g2d.drawImage(image, 0, 0, width, height, null);
        g2d.dispose();

        StringBuilder asciiArt = new StringBuilder();
        List<Future<String>> futures = new ArrayList<>();
        for (int y = 0; y < height; y++) {
            final int currentY = y;
            Future<String> future = executorService.submit(() -> {
                StringBuilder asciiRow = new StringBuilder();
                for (int x = 0; x < width; x++) {
                    int gray = new Color(scaledImage.getRGB(x, currentY)).getRed();
                    char asciiChar = grayToChar(gray);
                    asciiRow.append(asciiChar);
                }
                asciiRow.append("\n");
                return asciiRow.toString();
            });
            futures.add(future);
        }
        for (Future<String> future : futures) {
            try {
                asciiArt.append(future.get());
            } catch (InterruptedException | ExecutionException e) {
                e.printStackTrace();
            }
        }
        return asciiArt.toString();
    }

    private static char grayToChar(int gray) {
        final char[] chars = {'#', 'A', '@', '%', 'S', '<', '*', ':', ',', '.'};
        return chars[(gray * (chars.length - 1)) / 255];
    }

    private static class PlayAPIHandler implements com.sun.net.httpserver.HttpHandler {
        @Override
        public void handle(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/play")) {
                exchange.getResponseHeaders().set("Location", "/play/1");
                exchange.sendResponseHeaders(301, -1);
            } else {
                int frameNumber = Integer.parseInt(path.substring(path.lastIndexOf('/') + 1));
                Response response = playFrame(frameNumber);
                String frame = response.getEntity().toString();

                exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
                exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, OPTIONS");
                exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Origin, Content-Type, Accept");

                exchange.sendResponseHeaders(response.getStatus(), frame.length());
                exchange.getResponseBody().write(frame.getBytes());
            }
            exchange.close();
        }
    }

    private static class FrameSwitchTask extends TimerTask {
        @Override
        public void run() {
            currentFrame++;
            try {
                int totalFrames = totalFramesInVideo();
                if (currentFrame > totalFrames) {
                    currentFrame = 1;
                }
                server.removeContext("/play");
                server.createContext("/play", new PlayAPIHandler());
            } catch (IOException | JCodecException e) {
                e.printStackTrace();
            }
        }
    }

    private static int totalFramesInVideo() throws IOException, JCodecException {
        try (var channel = NIOUtils.readableChannel(new File(VIDEO_PATH))) {
            FrameGrab grab = FrameGrab.createFrameGrab(channel);
            return grab.getVideoTrack().getMeta().getTotalFrames();
        }
    }
}

