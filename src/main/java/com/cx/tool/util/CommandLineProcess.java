package com.cx.tool.util;

import java.io.*;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.*;

/**
 * @author Ilan Dayan
 */
public class CommandLineProcess {

    private String rootDirectory;
    private String[] args;
    private String userInput = null;

    private long timeoutReadLineSeconds = 55;
    private long timeoutProcessMinutes = 15;
    private boolean errorInProcess = false;
    private Process processStart = null;
    private File errorLog = new File("error.log");

    private static int errCount = 1;

    private static final String WINDOWS_SEPARATOR = "\\";
    private static final SimpleDateFormat DF = new SimpleDateFormat("MMddHHmmss");
    public static final String WHITESPACE = " ";
    public static final String OS_NAME = "os.name";

    public CommandLineProcess(String rootDirectory, String[] args, String userInput) {
        this.rootDirectory = rootDirectory;
        this.args = args;
        this.userInput = userInput;
    }

    public CommandLineProcess(String rootDirectory, String[] args) {
        this.rootDirectory = rootDirectory;
        this.args = args;
    }

    public List<String> executeProcess() throws IOException {
        return executeProcess(true, false);
    }

    private List<String> executeProcess(boolean includeOutput, boolean includeErrorLines) throws IOException {
        List<String> linesOutput = new LinkedList<>();
        ProcessBuilder pb = new ProcessBuilder(args);
        String osName = System.getProperty(OS_NAME);
        if (osName.startsWith("Windows")) {
            rootDirectory = getShortPath(rootDirectory);
        }
        pb.directory(new File(rootDirectory));
        // redirect the error output to avoid output of npm ls by operating system
        String redirectErrorOutput = isWindows() ? "nul" : "/dev/null";
        if (includeErrorLines) {
            pb.redirectError(errorLog);
        } else {
            pb.redirectError(new File(redirectErrorOutput));
        }
        if (!includeOutput || includeErrorLines) {
            pb.redirectOutput(new File(redirectErrorOutput));
        }
        if (!includeErrorLines) {
            System.out.println("Executing command:");
            System.out.println(rootDirectory + "> " + String.join(WHITESPACE, args));
        }
        this.processStart = pb.start();
        if (includeOutput) {
            ExecutorService executorService = Executors.newFixedThreadPool(1);
            BufferedReader reader;
            InputStreamReader inputStreamReader;
            if (this.userInput == null) {
                if (!includeErrorLines) {
                    inputStreamReader = new InputStreamReader(this.processStart.getInputStream());
                } else {
                    inputStreamReader = new InputStreamReader(this.processStart.getErrorStream());
                }
                reader = new BufferedReader(inputStreamReader);
                try {
                    this.errorInProcess = readBlock(inputStreamReader, reader, executorService, linesOutput, includeErrorLines);
                } catch (TimeoutException e) {
                    this.errorInProcess = true;
                    this.processStart.destroy();
                    return linesOutput;
                }
            } else {
                try {
                    OutputStream outputStream = this.processStart.getOutputStream();
                    BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(outputStream));
                    writer.write(this.userInput);
                    writer.write('\n');
                    writer.flush();
                    writer.close();

                    inputStreamReader = new InputStreamReader(this.processStart.getInputStream());
                    reader = new BufferedReader(inputStreamReader);
                    this.errorInProcess = readBlock(inputStreamReader, reader, executorService, linesOutput, includeErrorLines);
                } catch (Exception e) {
                    this.errorInProcess = true;
                    this.processStart.destroy();
                    return linesOutput;
                }
            }
        }
        try {
            this.processStart.waitFor(this.timeoutProcessMinutes, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            this.errorInProcess = true;
            System.out.println(args + " was interrupted, message: " + e.getMessage());
        }
        if (this.processStart.isAlive() && errorInProcess) {
            System.out.println("Error executing command destroying process");
            this.processStart.destroy();
            return linesOutput;
        }
        if (this.getExitStatus() != 0) {
            File dir = new File(this.rootDirectory);
            this.errorInProcess = true;
        }
        return linesOutput;
    }

    //get windows short path
    private String getShortPath(String rootPath) {
        File file = new File(rootPath);
        String lastPathAfterSeparator = null;
        String shortPath = getWindowsShortPath(file.getAbsolutePath());
        if (isNotEmpty(shortPath)) {
            return getWindowsShortPath(file.getAbsolutePath());
        } else {
            while (isEmpty(getWindowsShortPath(file.getAbsolutePath()))) {
                String filePath = file.getAbsolutePath();
                if (isNotEmpty(lastPathAfterSeparator)) {
                    lastPathAfterSeparator = file.getAbsolutePath().substring(filePath.lastIndexOf(WINDOWS_SEPARATOR), filePath.length()) + lastPathAfterSeparator;
                } else {
                    lastPathAfterSeparator = file.getAbsolutePath().substring(filePath.lastIndexOf(WINDOWS_SEPARATOR), filePath.length());
                }
                file = file.getParentFile();
            }
            return getWindowsShortPath(file.getAbsolutePath()) + lastPathAfterSeparator;
        }
    }

    private boolean readBlock(InputStreamReader inputStreamReader, BufferedReader reader, ExecutorService executorService,
                              List<String> lines, boolean includeErrorLines) throws TimeoutException, IOException {
        boolean wasError = false;
        boolean continueReadingLines = true;
        boolean timeout = false;
        try {
            if (!includeErrorLines) {
                System.out.println("trying to read lines using " + Arrays.toString(args));
            }
            int lineIndex = 1;
            String line = "";
            while (continueReadingLines && line != null) {
                Future<String> future = executorService.submit(new CommandLineProcess.ReadLineTask(reader));
                try {
                    line = future.get(this.timeoutReadLineSeconds, TimeUnit.SECONDS);
                    if (!includeErrorLines) {
                        if (isNotBlank(line)) {
                            System.out.println("Read line #" + line + ": " + line);
                            lines.add(line);
                        } else {
                            System.out.println("Finished reading" + (lineIndex - 1) + "lines");
                        }
                    } else {
                        if (isNotBlank(line)) {
                            lines.add(line);
                        }
                    }
                } catch (TimeoutException e) {
                    inputStreamReader = null;
                    reader = null;
                    continueReadingLines = false;
                    wasError = true;
                    timeout = true;
                } catch (Exception e) {
                    continueReadingLines = false;
                    wasError = true;
                }
                lineIndex++;
            }
        } catch (Exception e) {
        } finally {
            executorService.shutdown();
            inputStreamReader.close();
            reader.close();
        }
        if (timeout) {
            throw new TimeoutException("Failed reading line, probably user input is expected.");
        } else {
            return wasError;
        }
    }

    public int getExitStatus() {
        if (processStart != null) {
            return processStart.exitValue();
        }
        return 0;
    }

    private static boolean isNotBlank(String str) {
        return !isBlank(str);
    }

    private static boolean isBlank(String str) {
        int strLen;
        if (str == null || (strLen = str.length()) == 0) {
            return true;
        }
        for (int i = 0; i < strLen; i++) {
            if ((Character.isWhitespace(str.charAt(i)) == false)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isNotEmpty(String str) {
        return !isEmpty(str);
    }

    private static boolean isEmpty(String str) {
        return str == null || str.length() == 0;
    }

    private String getWindowsShortPath(String path) {
        return path;
    }

    private static boolean isWindows() {
        return System.getProperty(OS_NAME).toLowerCase().contains("win");
    }

    /* --- Nested classes --- */

    class ReadLineTask implements Callable<String> {

        /* --- Members --- */

        private final BufferedReader reader;

        /* --- Constructors --- */

        ReadLineTask(BufferedReader reader) {
            this.reader = reader;
        }

        /* --- Overridden methods --- */

        @Override
        public String call() throws Exception {
            return reader.readLine();
        }
    }

}
