package e.util;

import java.io.*;
import java.lang.reflect.*;
import java.util.*;

public class ProcessUtilities {
    /**
     * Runs 'command'. Returns the command's status code.
     * Lines written to standard output are appended to 'lines'.
     * Lines written to standard error are appended to 'errors'.
     * 
     * FIXME: should errors *we* detect go in 'lines', or in 'errors'? Currently
     * they go in 'lines'.
     * 
     * If directory is null, the subprocess inherits our working directory.
     * 
     * You can use the same ArrayList for 'lines' and 'errors'. All the error
     * lines will appear after all the output lines.
     */
    public static int backQuote(File directory, String[] command, ArrayList lines, ArrayList errors) {
        ArrayList result = new ArrayList();
        try {
            Process p = Runtime.getRuntime().exec(command, null, directory);
            p.getOutputStream().close();
            readLinesFromStream(lines, p.getInputStream());
            readLinesFromStream(errors, p.getErrorStream());
            return p.waitFor();
        } catch (Exception ex) {
            ex.printStackTrace();
            lines.add(ex.getMessage());
            return 1;
        }
    }

    /**
     * Runs a command and ignores the output. The child is waited for on a
     * separate thread, so there's no indication of whether the spawning was
     * successful or not. A better design might be to exec in the current
     * thread, and hand the Process over to another Thread; you'd still not
     * get the return code (but losing that is part of the deal), but you
     * would at least know that Java had no trouble in exec. Is that worth
     * anything?
     */
    public static void spawn(final File directory, final String[] command) {
        new Thread() {
            public void run() {
                try {
                    Process p = Runtime.getRuntime().exec(command, null, directory);
                    p.getOutputStream().close();
                    p.waitFor();
                } catch (Exception ex) {
                    ex.printStackTrace();
                }
            }
        }.start();
    }

    private static void readLinesFromStream(ArrayList result, InputStream stream) {
        BufferedReader in = null;
        try {
            in = new BufferedReader(new InputStreamReader(stream));
            String line;
            while ((line = in.readLine()) != null) {
                result.add(line);
            }
            in.close();
        } catch (Exception ex) {
            ex.printStackTrace();
            result.add(ex.getMessage());
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Exception ex) {
                    ex.printStackTrace();
                    result.add(ex.getMessage());
                }
            }
        }
    }
    
    /**
     * Returns the process id of the given process, or -1 if we couldn't
     * work it out.
     */
    public static int getProcessId(Process process) {
        try {
            Field pidField = process.getClass().getDeclaredField("pid");
            pidField.setAccessible(true);
            return pidField.getInt(process);
        } catch (Exception ex) {
            return -1;
        }
    }
    
    /** The HUP (hang up) signal. */
    public static final int SIGHUP = 1;
    
    /** The INT (interrupt) signal. */
    public static final int SIGINT = 2;
    
    /** The QUIT (quit) signal. */
    public static final int SIGQUIT = 3;
    
    /** The KILL (non-catchable, non-ignorable kill) signal. */
    public static final int SIGKILL = 9;
    
    /** The TERM (soft termination) signal. */
    public static final int SIGTERM = 15;
    
    /**
     * Sends the given signal to the given process. Returns false if
     * the signal could not be sent.
     */
    public static boolean signalProcess(Process process, int signal) {
        int pid = getProcessId(process);
        if (pid == -1) {
            return false;
        }
        try {
            Runtime.getRuntime().exec("kill -" + signal + " " + pid);
            return true;
        } catch (IOException ex) {
            return false;
        }
    }
    
    /**
     * Returns the integer file descriptor corresponding to one of
     * FileDescriptor.in, FileDescriptor.out or FileDescriptor.err
     * for the given process. Returns -1 on error.
     */
    public static int getFd(Process process, FileDescriptor which) {
        if (which == FileDescriptor.in) {
            return getFd(process, "stdin_fd");
        } else if (which == FileDescriptor.out) {
            return getFd(process, "stdout_fd");
        } else if (which == FileDescriptor.err) {
            return getFd(process, "stderr_fd");
        } else {
            return -1;
        }
    }
    
    private static int getFd(Process process, String which) {
        try {
            Field fileDescriptorField = process.getClass().getDeclaredField(which);
            fileDescriptorField.setAccessible(true);
            FileDescriptor fileDescriptor = (FileDescriptor) fileDescriptorField.get(process);
            Field fdField = FileDescriptor.class.getDeclaredField("fd");
            fdField.setAccessible(true);
            return fdField.getInt(fileDescriptor);
        } catch (Exception ex) {
            return -1;
        }
    }
    
    /** Prevents instantiation. */
    private ProcessUtilities() {
    }
}
