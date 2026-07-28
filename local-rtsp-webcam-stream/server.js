require("dotenv").config();
const express = require("express");
const { spawn } = require("child_process");
const path = require("path");

const app = express();
const PORT = process.env.PORT || 8080;
const RTSP_URL = process.env.RTSP_URL;
const BOUNDARY = "frame";

if (!RTSP_URL) {
  console.error("Missing RTSP_URL. Copy .env.example to .env and set your camera URL.");
  process.exit(1);
}

app.use(express.static(path.join(__dirname, "public")));

app.get("/stream", (req, res) => {
  res.writeHead(200, {
    "Content-Type": `multipart/x-mixed-replace; boundary=${BOUNDARY}`,
    "Cache-Control": "no-cache",
    Connection: "close",
  });

  const ffmpeg = spawn("ffmpeg", [
    "-rtsp_transport", "tcp",
    "-i", RTSP_URL,
    "-f", "mpjpeg",
    "-boundary_tag", BOUNDARY,
    "-q:v", "5",
    "-r", "15",
    "-an",
    "pipe:1",
  ]);

  ffmpeg.stdout.pipe(res);
  ffmpeg.stderr.on("data", () => {});

  ffmpeg.on("error", (err) => {
    console.error("Failed to start ffmpeg (is it installed?):", err.message);
    res.end();
  });

  req.on("close", () => ffmpeg.kill("SIGKILL"));
});

app.listen(PORT, "0.0.0.0", () => {
  console.log(`RTSP viewer running. Open http://<this-machine's-LAN-IP>:${PORT} on your phone.`);
});
