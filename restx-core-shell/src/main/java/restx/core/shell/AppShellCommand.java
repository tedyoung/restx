package restx.core.shell;

import com.github.mustachejava.Mustache;
import com.google.common.base.Charsets;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.io.CharStreams;
import com.google.common.io.Files;
import com.google.common.io.Resources;
import jline.console.completer.ArgumentCompleter;
import jline.console.completer.Completer;
import jline.console.completer.StringsCompleter;
import restx.AppSettings;
import restx.Apps;
import restx.build.RestxBuild;
import restx.common.UUIDGenerator;
import restx.common.Version;
import restx.factory.Component;
import restx.factory.NamedComponent;
import restx.factory.SingletonFactoryMachine;
import restx.shell.RestxShell;
import restx.shell.ShellCommandRunner;
import restx.shell.StdShellCommand;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * User: xavierhanin
 * Date: 4/10/13
 * Time: 8:53 PM
 */
@Component
public class AppShellCommand extends StdShellCommand {
    public AppShellCommand() {
        super(ImmutableList.of("app"), "app related commands: creates a new app, run your app, ...");
    }

    @Override
    protected Optional<? extends ShellCommandRunner> doMatch(String line) {
        List<String> args = splitArgs(line);

        if (args.size() < 2) {
            return Optional.absent();
        }

        switch (args.get(1)) {
            case "new":
                return Optional.of(new NewAppCommandRunner());
            case "compile":
                return Optional.of(new CompileAppCommandRunner(args));
            case "generate-start-script":
                return Optional.of(new GenerateStartCommandRunner(args));
            case "run":
                return Optional.of(new RunAppCommandRunner(args));
        }

        return Optional.absent();
    }

    @Override
    public void man(Appendable appendable) throws IOException {
        super.man(appendable);
    }

    protected String resourceMan() {
        return "restx/core/shell/app.man";
    }

    @Override
    public Iterable<Completer> getCompleters() {
        return ImmutableList.<Completer>of(new ArgumentCompleter(
                new StringsCompleter("app"), new StringsCompleter("new", "run", "compile", "generate-start-script")));
    }

    static class NewAppDescriptor {
        String appName;
        String groupId;
        String artifactId;
        String mainPackage;
        String version;
        String buildFile;
        String signatureKey;
        String adminPassword;
        String defaultPort;
        String basePath;
        String restxVersion;
        boolean generateHelloResource;
    }

    class NewAppCommandRunner implements ShellCommandRunner {

        private ImmutableMap<Mustache, String> mainTemplates = buildTemplates(
                AppShellCommand.class, ImmutableMap.<String, String>builder()
                .put("md.restx.json.mustache", "md.restx.json")
                .put("AppModule.java.mustache", "src/main/java/{{packagePath}}/AppModule.java")
                .put("AppServer.java.mustache", "src/main/java/{{packagePath}}/AppServer.java")
                .put("UserRepository.java.mustache", "src/main/java/{{packagePath}}/persistence/UserRepository.java")
                .put("web.xml.mustache", "src/main/webapp/WEB-INF/web.xml")
                .put("logback.xml.mustache", "src/main/resources/logback.xml")
                .build()
        );
        private ImmutableMap<Mustache, String> helloResourceTemplates = buildTemplates(
                AppShellCommand.class, ImmutableMap.<String, String>builder()
                .put("Message.java.mustache", "src/main/java/{{packagePath}}/domain/Message.java")
                .put("HelloResource.java.mustache", "src/main/java/{{packagePath}}/rest/HelloResource.java")
                .put("HelloResourceSpecTest.java.mustache", "src/test/java/{{packagePath}}/rest/HelloResourceSpecTest.java")
                .put("should_admin_say_hello.spec.yaml.mustache", "src/test/resources/specs/hello/should_admin_not_say_hello.spec.yaml")
                .put("should_user1_say_hello.spec.yaml.mustache", "src/test/resources/specs/hello/should_user1_say_hello.spec.yaml")
                .put("should_user2_not_say_hello.spec.yaml.mustache", "src/test/resources/specs/hello/should_user2_not_say_hello.spec.yaml")
                .build()
        );

        @Override
        public void run(RestxShell shell) throws Exception {
            shell.printIn("Welcome to RESTX APP bootstrap!", RestxShell.AnsiCodes.ANSI_GREEN);
            shell.println("");
            shell.println("This command will ask you a few questions to generate your brand new RESTX app.");
            shell.println("For any question you can get help by answering '??' (without the quotes).");
            shell.println("");

            NewAppDescriptor descriptor = new NewAppDescriptor();
            descriptor.appName = "";
            while (Strings.isNullOrEmpty(descriptor.appName)) {
                descriptor.appName = shell.ask("App name? ", "",
                        "This is the name of the application you are creating.\n" +
                                "It can contain spaces, it's used mainly for documentation and to provide default for other values.\n" +
                                "Examples: Todo, Foo Bar, ...");
            }

            descriptor.groupId = shell.ask("group id [%s]? ",
                    descriptor.appName.replaceAll("\\s+", "-").toLowerCase(Locale.ENGLISH),
                    "This is the identifier of the group or organization producing the application.\n" +
                            "In the Maven world this is called a groupId, in Ivy it's called organization.\n" +
                            "It MUST NOT contain spaces nor columns (':'), and is usually a reversed domain name.\n" +
                            "Examples: io.restx, com.example, ...");
            descriptor.artifactId = shell.ask("artifact id [%s]? ",
                    descriptor.appName.replaceAll("\\s+", "-").toLowerCase(Locale.ENGLISH),
                    "This is the identifier of the app module.\n" +
                            "In the Maven world this is called an artifactId, in Ivy it's called module.\n" +
                            "It MUST NOT contain spaces nor columns (':'), and is usually a dash separated lower case word.\n" +
                            "Examples: myapp, todo, foo-app, ...")
                    .replaceAll("\\s+", "-");
            descriptor.mainPackage = shell.ask("main package [%s]? ",
                    descriptor.groupId.replaceAll("\\-", ".").toLowerCase(Locale.ENGLISH),
                    "This is the main package in which you will develop your application.\n" +
                            "In Java convention it should start with a reversed domain name followed by the app name\n" +
                            "but for applications (as opposed to APIs) we prefer to use a short name, like that app name.\n" +
                            "It MUST follow Java package names restrictions, so MUST NOT contain spaces\n" +
                            "Examples: myapp, com.example.todoapp, ...");
            descriptor.version = shell.ask("version [%s]? ", "0.1-SNAPSHOT",
                    "This is the name of the first version of the app you are targetting.\n" +
                            "It's recommended to use Maven convention to suffix it with -SNAPSHOT if you plan to use Maven for your app\n" +
                            "Examples: 0.1-SNAPSHOT, 1.0, ...");

            descriptor.buildFile = shell.ask("generate module descriptor (ivy/pom/none/all) [%s]? ", "all",
                    "This allows to generate a module descriptor for your app.\n" +
                            "Options:\n" +
                            "\t- 'ivy': get an Easyant compatible Ivy file generated for you.\n" +
                            "\t- 'pom': get a Maven POM generated for you.\n" +
                            "\t- 'all': get both a POM and an Ivy file.\n" +
                            "\t- 'none': get no module descriptor generated. WARNING: this will make it harder to build your app.\n" +
                            "If you don't know these tools, use default answer.\n"
            );

            descriptor.restxVersion = shell.ask("restx version [%s]? ", Version.getVersion("io.restx", "restx-core"));

            List<String> list = Lists.newArrayList(UUIDGenerator.DEFAULT.doGenerate(),
                    String.valueOf(new Random().nextLong()), descriptor.appName, descriptor.artifactId);
            Collections.shuffle(list);
            descriptor.signatureKey = shell.ask("signature key (to sign cookies) [%s]? ",
                    Joiner.on(" ").join(list),
                    "This is used as salt for signing stuff exchanged with the client.\n" +
                            "Use something fancy or keep what is proposed by default, but make sure to not share that publicly.");

            descriptor.adminPassword = shell.ask("admin password (to authenticate on restx console) [%s]? ",
                    String.valueOf(new Random().nextInt(10000)),
                    "This is used as password for the admin user to authenticate on restx console.\n" +
                            "This is only a default way to authenticate out of the box, restx security is very flexible.");

            descriptor.defaultPort = shell.ask("default port [%s]? ", "8080",
                    "This is the default port used when using embedded version.\n" +
                            "Usually Java web containers use 8080, it may be a good idea to use a different port to avoid \n" +
                            "conflicts with another servlet container.\n" +
                            "You can also use port 80 if you want to serve your API directly with the embedded server\n" +
                            "and no reverse proxy in front of it. But beware that you may need admin privileges for that.\n" +
                            "Examples: 8080, 8086, 8000, 80");
            descriptor.basePath = shell.ask("base path [%s]? ", "/api",
                    "This is the base API path on which RESTX will handle requests.\n" +
                            "Being focused on REST API only, RESTX is usually shared with either static or dynamic \n" +
                            "resources serving (HTML, CSS, JS, images, ...) and therefore is used to handle requests on\n" +
                            "only a sub path of the web app.\n" +
                            "If you plan to use it to serve requests from an API only domain (eg api.example.com)\n" +
                            "you can use '' (empty string) for this path.\n" +
                            "Examples: /api, /api/v2, /restx, ...");

            descriptor.generateHelloResource = shell.askBoolean("generate hello resource example [Y/n]? ", "y",
                    "This will generate an example resource with an associated spec test so that your boostrapped\n" +
                            "application can be used as soon as it has been generated.\n" +
                            "If this is the first app you generate with RESTX, it's probably a good idea to generate\n" +
                            "this example resource.\n" +
                            "If you already know RESTX by heart you shouldn't be reading this message anyway :)");

            Path appPath = generateApp(descriptor, shell);

            shell.cd(appPath);

            if (shell.askBoolean("Do you want to install its deps and run it now? [Y/n]", "Y",
                    "By answering yes restx will resolve and install the dependencies of the app and run it.\n" +
                            "You can always install the deps later by using the `deps install` command\n" +
                            "and run the app with the `app run` command")) {

                shell.println("restx> deps install");
                new DepsShellCommand().new InstallDepsCommandRunner().run(shell);
                shell.println("restx> app run");
                new RunAppCommandRunner(Collections.<String>emptyList()).run(shell);
            }
        }

        public Path generateApp(NewAppDescriptor descriptor, RestxShell shell) throws IOException {
            boolean generateIvy = "ivy".equalsIgnoreCase(descriptor.buildFile) || "all".equalsIgnoreCase(descriptor.buildFile);
            boolean generatePom = "pom".equalsIgnoreCase(descriptor.buildFile) || "all".equalsIgnoreCase(descriptor.buildFile);

            ImmutableMap scope = ImmutableMap.builder()
                    .put("appName", descriptor.appName)
                    .put("groupId", descriptor.groupId)
                    .put("artifactId", descriptor.artifactId)
                    .put("mainPackage", descriptor.mainPackage)
                    .put("packagePath", descriptor.mainPackage.replace('.', '/'))
                    .put("version", descriptor.version)
                    .put("signatureKey", descriptor.signatureKey)
                    .put("adminPassword", descriptor.adminPassword)
                    .put("defaultPort", descriptor.defaultPort)
                    .put("basePath", descriptor.basePath)
                    .put("restxVersion", descriptor.restxVersion)
                    .build();

            Path appPath = shell.currentLocation().resolve(descriptor.artifactId);

            shell.println("scaffolding app to `" + appPath.toAbsolutePath() + "` ...");
            generate(mainTemplates, appPath, scope);

            if (generateIvy) {
                shell.println("generating module.ivy ...");
                RestxBuild.convert(appPath.toAbsolutePath() + "/md.restx.json", appPath.toAbsolutePath() + "/module.ivy");
            }
            if (generatePom) {
                shell.println("generating pom.xml ...");
                RestxBuild.convert(appPath.toAbsolutePath() + "/md.restx.json", appPath.toAbsolutePath() + "/pom.xml");
            }

            if (descriptor.generateHelloResource) {
                shell.println("generating hello resource ...");
                generate(helloResourceTemplates, appPath, scope);
            }
            shell.printIn("Congratulations! - Your app is now ready in " + appPath.toAbsolutePath(), RestxShell.AnsiCodes.ANSI_GREEN);
            shell.println("");
            shell.println("");

            return appPath;
        }
    }

    private class CompileAppCommandRunner implements ShellCommandRunner {
        private ShellAppRunner.CompileMode compileMode = ShellAppRunner.CompileMode.ALL;

        public CompileAppCommandRunner(List<String> args) {
        }

        @Override
        public void run(RestxShell shell) throws Exception {
            AppSettings appSettings = shell.getFactory()
                    .getComponent(AppSettings.class);
            compileMode.compile(
                    shell,
                    Paths.get(appSettings.targetClasses()),
                    Paths.get(appSettings.targetDependency()),
                    Paths.get(appSettings.mainSources()),
                    Paths.get(appSettings.mainResources()),
                    null);
        }
    }

    private class GenerateStartCommandRunner implements ShellCommandRunner {
        private String appClassName;

        public GenerateStartCommandRunner(List<String> args) {
            if (args.size() > 2) {
                appClassName = args.get(2);
            }
        }

        @Override
        public void run(RestxShell shell) throws Exception {
            AppSettings appSettings = shell.getFactory()
                    .getComponent(AppSettings.class);
            Optional<String> pack = Optional.absent();
            if (appClassName == null) {
                pack = Apps.with(appSettings)
                        .guessAppBasePackage(shell.currentLocation());
                if (!pack.isPresent()) {
                    shell.printIn("can't find base app package, src/main/java should contain a AppServer.java source file somewhere",
                            RestxShell.AnsiCodes.ANSI_RED);
                    shell.println("");
                    shell.println("alternatively you can provide the class to run with `app generate-start-script <class.to.Run>`");
                    return;
                }
                appClassName = pack.get() + ".AppServer";
            } else {
                pack = Optional.of(appClassName.substring(0, appClassName.lastIndexOf('.')));
            }



            String command = "java" +
                    " -cp \"" + appSettings.targetClasses() + ":" + appSettings.targetDependency() + "/*\"" +
                    " -Drestx.app.package=" + pack.get()
                    ;

            File startSh = shell.currentLocation().resolve("start.sh").toFile();
            Files.write(
                    "#!/bin/sh\n\n" +
                            command + " $VM_OPTIONS " +
                            " " + appClassName + "\n",
                    startSh, Charsets.UTF_8);
            startSh.setExecutable(true);

            File startBat = shell.currentLocation().resolve("start.bat").toFile();
            Files.write(
                    command + " %VM_OPTIONS% " +
                    " " + appClassName + "\r\n",
                    startBat, Charsets.ISO_8859_1);

            shell.printIn("generated start scripts:\n" +
                    "\t" + startSh.getAbsolutePath() + "\n" +
                    "\t" + startBat.getAbsolutePath() + "\n",
                    RestxShell.AnsiCodes.ANSI_GREEN);
        }
    }

    private class RunAppCommandRunner implements ShellCommandRunner {
        private String appClassName;
        private boolean quiet;
        private boolean daemon;
        private ShellAppRunner.CompileMode compileMode = ShellAppRunner.CompileMode.MAIN_CLASS;
        private List<String> vmOptions = new ArrayList<>();

        public RunAppCommandRunner(List<String> args) {
            args = new ArrayList<>(args);
            quiet = false;
            daemon = true;

            while (args.size() > 2) {
                String arg = args.get(2);
                if (arg.equalsIgnoreCase("--quiet")) {
                    quiet = true;
                } else if (arg.startsWith("--fg")) {
                    daemon = false;
                } else if (arg.startsWith("--mode=")) {
                    String mode = arg.substring("--mode=".length());
                    vmOptions.add("-Drestx.mode=" + mode);
                    if (mode.equals("prod")) {
                        compileMode = ShellAppRunner.CompileMode.ALL;
                    }
                } else if (arg.startsWith("-D") || arg.startsWith("-X")) {
                    vmOptions.add(arg);
                } else if (appClassName == null) {
                    appClassName = arg;
                } else {
                    throw new IllegalArgumentException("app run argument not recognized: " + arg);
                }
                args.remove(2);
            }
        }

        @Override
        public void run(RestxShell shell) throws Exception {
            if (appClassName == null) {
                Optional<String> pack = Apps.with(shell.getFactory().getComponent(AppSettings.class))
                                                .guessAppBasePackage(shell.currentLocation());
                if (!pack.isPresent()) {
                    shell.printIn("can't find base app package, src/main/java should contain a AppServer.java source file somewhere",
                            RestxShell.AnsiCodes.ANSI_RED);
                    shell.println("");
                    shell.println("alternatively you can provide the class to run with `app run <class.to.Run>`");
                    return;
                }
                appClassName = pack.get() + ".AppServer";
            }

            if (!DepsShellCommand.depsUpToDate(shell)) {
                shell.println("restx> deps install");
                new DepsShellCommand().new InstallDepsCommandRunner().run(shell);
            }

            String basePack = appClassName.substring(0, appClassName.lastIndexOf('.'));
            AppSettings appSettings = shell.getFactory()
                    .concat(new SingletonFactoryMachine<>(-10000, NamedComponent.of(String.class, "restx.app.package", basePack)))
                    .getComponent(AppSettings.class);
            new ShellAppRunner(appSettings, appClassName, compileMode, quiet, daemon, vmOptions)
                .run(shell);
        }
    }
}
