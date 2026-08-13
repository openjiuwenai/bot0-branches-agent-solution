import java.io.File;

/** Windows process launcher used by start-runtime-verification.ps1. */
public final class RuntimeVerificationLauncher {
    public static void main(String[] args) throws Exception {
        if (args.length != 8) {
            throw new IllegalArgumentException("expected: mockCp mockPort appCp appPort runtimeUrl mockLog appLog workDir");
        }
        File workDir = new File(args[7]);
        Process mock = start(args[0], "com.openjiuwen.mockruntime.MockRuntimeServer",
                new String[] {args[1]}, new File(args[5]), workDir);
        Process app = start(args[2], "com.openjiuwen.client.runtimeverify.RuntimeVerificationApp",
                new String[] {args[3], args[4]}, new File(args[6]), workDir);
        System.out.println(mock.pid() + "," + app.pid());
    }

    private static Process start(String classpath, String mainClass, String[] arguments,
            File log, File workDir) throws Exception {
        java.util.List<String> command = new java.util.ArrayList<>();
        command.add(new File(System.getProperty("java.home"), "bin/java.exe").getAbsolutePath());
        command.add("-cp");
        command.add(classpath);
        command.add(mainClass);
        java.util.Collections.addAll(command, arguments);
        return new ProcessBuilder(command).directory(workDir)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(log))
                .redirectError(ProcessBuilder.Redirect.appendTo(log))
                .start();
    }
}
