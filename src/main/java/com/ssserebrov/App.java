package com.ssserebrov;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Comparator;
import java.util.Date;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.TimeUnit;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.time.DateUtils;

public class App {

//  public static final String OUTPUT_DIR = "output";

  private static String workingDir;

  private static  Path outputDir = Paths.get(System.getProperty("user.dir"), "output");

  private static  Path uploadDir = Paths.get(outputDir.toString(), "upload");

  enum Mode {NORMAL, COMPILE_MONTH_VIDEO}

  private static Mode mode = Mode.NORMAL;

  public static void main(String[] args)
      throws InterruptedException, IOException {

    String month = null;

    for (int i = 0; i < args.length; i++) {
      if (args[i].equals("-m")) {
        mode = Mode.COMPILE_MONTH_VIDEO;
        month = args[i++];
      }
      if (args[i].equals("-w")) {
        workingDir = args[i++];
      }
    }

    outputDir = Paths.get(workingDir, "output");
    uploadDir = Paths.get(outputDir.toString(), "upload");

    if (mode == Mode.COMPILE_MONTH_VIDEO) {
      complileMonthly(month);
    } else {
      System.out.println("Starting...");
      scheduleFrameGrabber();
      scheduleDailyCompliler();
      System.out.println("Started");
    }
    Thread.sleep(Long.MAX_VALUE);
  }

  private static void scheduleDailyCompliler() {
    long seconds = 60L * 60L * 24L;
    Date startTime = DateUtils.ceiling(new Date(), Calendar.DATE);

    Timer timer = new Timer(true);
    timer.scheduleAtFixedRate(new TimerTask() {
      @Override
      public void run() {
        try {
          complileDaily();
        } catch (Exception e) {
          e.printStackTrace();
        }
      }
    }, startTime, 1000 * seconds);
    System.out.println("scheduled Daily Compliler: " + startTime.toString());

  }

  private static void runffmpegFor1Hour() throws IOException, InterruptedException {
    File hourDir = createHourDir(LocalDateTime.now());

    ProcessBuilder processBuilder = new ProcessBuilder(
        "ffmpeg",
        "-rtsp_transport", "tcp",
        "-i",
        "rtsp://192.168.1.137:554/user=admin&password=22312231&channel=1&stream=0.sdp",
        "-y",
        "-f", "image2",
        "-r", "0.5",
        "CAM3-" + hourDir.getName() + "%04d.jpg");

    processBuilder.redirectErrorStream(true); // redirect error stream to output stream
    processBuilder.redirectOutput(ProcessBuilder.Redirect.INHERIT);
    processBuilder.directory(hourDir);

    Process process = processBuilder.start();
    process.waitFor(60 * 60 - 2, TimeUnit.SECONDS);
    process.destroy();
    process.waitFor(2, TimeUnit.SECONDS);
    process.destroyForcibly();
    // wait for termination.
    process.waitFor();
    System.out.println(new Date().toString());
//    runVideoCompiler(processBuilder.directory());
    copyHourFrame(processBuilder.directory());
  }

  // must be called not late than 1 hour
  private static void complileDaily() throws InterruptedException, IOException {
    LocalDateTime date = LocalDateTime.now().minusHours(1);
    File dayDir = createHourDir(date).getParentFile();
    copyDayFrames(dayDir);

    Path tempPath = Paths.get(dayDir.toString(), "frames");
    Path outputFilePath = null;

    SimpleDateFormat sdf = new SimpleDateFormat("yyyy.dd.MM");
    String outputFileName = sdf.format(java.sql.Timestamp.valueOf(date));
    try {
      outputFilePath = complileDir(tempPath.toFile(), outputFileName, "CAM3-%05d.jpg");
    } finally {
      FileUtils.deleteQuietly(tempPath.toFile());

      if (isValidDailyTimelapse(outputFilePath)) {
        File[] dirs = dayDir.listFiles(File::isDirectory);
        Arrays.stream(dirs)
            .forEach(FileUtils::deleteQuietly);
      }
    }

  }

  private static void complileMonthly(String monthDir) throws InterruptedException, IOException {
    Path monthPath = Paths.get(outputDir.toString(), monthDir);
    Path tempPath = Paths.get(outputDir.toString(), "monthly");

    File[] dayDirs = monthPath.toFile().listFiles(File::isDirectory);
    Integer fileCount = 0;
    for (File dayDir : dayDirs) {
      File[] frames = dayDir.listFiles();
      Arrays.sort(frames, Comparator.comparingLong(File::lastModified));
      for (File frame : frames) {
        if (frame.length() == 0) {
          continue;
        }

        String newFrameFileName = StringUtils.leftPad((++fileCount).toString(), 6, "0") + ".jpg";
        FileUtils
            .copyFile(frame, Paths.get(tempPath.toString(), "CAM3-" + newFrameFileName).toFile());
      }

    }

    complileDir(tempPath.toFile(), monthDir, "CAM3-%06d.jpg");


  }

  private static Path complileDir(File inputDir, String outputFilename, String pattern)
      throws InterruptedException, IOException {
    System.out.println("Start compilation");
    outputFilename += ".mp4";
    ProcessBuilder processBuilder = new ProcessBuilder(
        "ffmpeg",
        "-r", "60",
        "-f", "image2",
        "-i", pattern,
        outputFilename);
    processBuilder.redirectErrorStream(true); // redirect error stream to output stream
    processBuilder.redirectOutput(ProcessBuilder.Redirect.INHERIT);
    processBuilder.directory(inputDir);

    Process process = processBuilder.start();
    process.waitFor();

    Path outputFilePath = Paths.get(uploadDir.toString(), outputFilename);

    FileUtils
        .moveFile(Paths.get(inputDir.getAbsolutePath(), outputFilename).toFile(),
                  outputFilePath.toFile());

    System.out.println("Finish compilation");

    return outputFilePath;

  }

  private static boolean isValidDailyTimelapse(Path path) {
    return path != null && path.toFile().length() > 550 * 1024 * 1024;
  }

  private static void copyHourFrame(File hourDir) throws IOException {
    FilenameFilter jpgFilter = (dir, name) -> name.toLowerCase().endsWith(".jpg");

    File[] frames = hourDir.listFiles(jpgFilter);
    if (frames != null && frames.length > 0) {
      Arrays.sort(frames, Comparator.comparingLong(File::lastModified));
      FileUtils.copyFileToDirectory(frames[0], hourDir.getParentFile());
    }
  }

  private static void copyDayFrames(File dayDir) throws IOException {
    FilenameFilter jpgFilter = (dir, name) -> name.toLowerCase().endsWith(".jpg");
    File outputDir = Paths.get(dayDir.toString(), "frames").toFile();

    File[] hourDirs = dayDir.listFiles(File::isDirectory);
    Integer fileCount = 0;
    for (File hourDir : hourDirs) {
      File[] frames = hourDir.listFiles(jpgFilter);
      Arrays.sort(frames, Comparator.comparingLong(File::lastModified));
      for (File frame : frames) {
        if (frame.length() == 0) {
          continue;
        }

        String newFrameFileName = StringUtils.leftPad((++fileCount).toString(), 5, "0") + ".jpg";
        FileUtils
            .copyFile(frame, Paths.get(outputDir.toString(), "CAM3-" + newFrameFileName).toFile());
      }

    }
  }

  private static File createHourDir(LocalDateTime datez) {
    Date date = java.sql.Timestamp.valueOf(datez);

    SimpleDateFormat sdfM = new SimpleDateFormat("yyyyMM");
    SimpleDateFormat sdfD = new SimpleDateFormat("dd");
    SimpleDateFormat sdfH = new SimpleDateFormat("HH");
    String mouthString = sdfM.format(date);
    String dayString = sdfD.format(date);
    String hourString = sdfH.format(date);

    File hourDir = Paths.get(outputDir.toString(), mouthString, dayString, hourString).toFile();
    if (!hourDir.exists()) {
      hourDir.mkdirs();
    }

    return hourDir;
  }

  private static void scheduleFrameGrabber() {
    long seconds = 60L * 60L;
    Date startTime = DateUtils.ceiling(new Date(), Calendar.HOUR);

    Timer timer = new Timer(true);
    timer.scheduleAtFixedRate(new TimerTask() {
      @Override
      public void run() {
        try {
          runffmpegFor1Hour();
        } catch (Exception e) {
          e.printStackTrace();
        }
      }
    }, startTime, 1000 * seconds);

    System.out.println("scheduled Frame Grabber: " + startTime.toString());
  }

  private static void runVideoCompiler(File dir) {
    Timer timer = new Timer(true);
    timer.schedule(new TimerTask() {
      @Override
      public void run() {
        try {
          complileDir(dir, dir.getName(), "CAM3-%05d.jpg");
        } catch (Exception e) {
          e.printStackTrace();
        }
      }
    }, 10000);
  }


}