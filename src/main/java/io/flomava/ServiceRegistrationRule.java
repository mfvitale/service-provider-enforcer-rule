package io.flomava;

import org.apache.maven.enforcer.rule.api.AbstractEnforcerRule;
import org.apache.maven.enforcer.rule.api.EnforcerRuleException;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.project.MavenProject;
import org.apache.maven.rtinfo.RuntimeInformation;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.ModuleNode;
import org.objectweb.asm.tree.ModuleProvideNode;


import javax.inject.Inject;
import javax.inject.Named;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Stream;

@Named("serviceRegistrationRule")
public class ServiceRegistrationRule extends AbstractEnforcerRule {

    public static final String CLASS_FILE_EXTENTION = ".class";
    public static final String INNER_CLASS_PREFIX = "$";
    public static final String OBJECT_CLASS = "java/lang/Object";
    private final MavenSession session;
    private final MavenProject project;
    private final RuntimeInformation runtimeInformation;

    /**
     * List of interfaces that implementations should be registered for
     */
    private List<String> serviceInterfaces = new ArrayList<>();

    /**
     * Packages to scan for implementations (defaults to scanning all packages if empty)
     */
    private List<String> packagesToScan = new ArrayList<>();

    /**
     * Strategy for handling missing service registrations
     */
    private EnforcementStrategy strategy = EnforcementStrategy.FAIL;

    public enum EnforcementStrategy {
        FAIL,  // Fail the build if implementations are not declared in META-INF
        WARN   // Just warn if implementations are not declared in META-INF
    }

    @Inject
    public ServiceRegistrationRule(MavenProject project, MavenSession session, RuntimeInformation runtimeInformation) {
        this.project = Objects.requireNonNull(project);
        this.session = Objects.requireNonNull(session);
        this.runtimeInformation = Objects.requireNonNull(runtimeInformation);
    }

    public void execute() throws EnforcerRuleException {

        if (serviceInterfaces == null || serviceInterfaces.isEmpty()) {
            throw new EnforcerRuleException("serviceInterfaces parameter is required and must not be empty");
        }

        try {
            String outputDirectory = project.getBuild().getOutputDirectory();
            Path classesDir = Paths.get(outputDirectory);

            if (!Files.exists(classesDir)) {
                getLog().info("No compiled classes found, skipping service registration check");
                return;
            }

            getLog().info("Checking service registration for interfaces: " + serviceInterfaces);
            getLog().info("Using enforcement strategy: " + strategy);
            getLog().info("Scanning packages: " + (packagesToScan.isEmpty() ? "ALL" : packagesToScan));

            boolean hasViolations = false;
            Map<String, ServiceCheckResult> results = new HashMap<>();

            for (String serviceInterface : serviceInterfaces) {
                ServiceCheckResult result = checkServiceInterface(classesDir, serviceInterface);
                results.put(serviceInterface, result);

                if (!result.unregistered().isEmpty() || !result.nonExistent().isEmpty()) {
                    hasViolations = true;
                }
            }

            reportResults(results);

            if (hasViolations && strategy == EnforcementStrategy.FAIL) {
                throw new EnforcerRuleException("Service registration violations found. See messages above for details.");
            }

        } catch (IOException e) {
            throw new EnforcerRuleException("Error scanning for service implementations", e);
        }
    }

    private ServiceCheckResult checkServiceInterface(Path classesDir, String serviceInterface) throws IOException {

        Set<String> implementations = findImplementations(classesDir, serviceInterface);
        getLog().debug("Found " + implementations.size() + " implementations of " + serviceInterface + ": " + implementations);

        Set<String> registeredServices = readRegisteredServices(classesDir, serviceInterface);
        getLog().debug("Found " + registeredServices.size() + " registered services for " + serviceInterface + ": " + registeredServices);

        Set<String> unregistered = new HashSet<>(implementations);
        unregistered.removeAll(registeredServices);

        Set<String> nonExistent = new HashSet<>(registeredServices);
        nonExistent.removeAll(implementations);

        return new ServiceCheckResult(serviceInterface, implementations, registeredServices, unregistered, nonExistent);
    }

    private void reportResults(Map<String, ServiceCheckResult> results) {

        for (ServiceCheckResult result : results.values()) {
            String interfaceName = result.serviceInterface();

            if (result.unregistered().isEmpty() && result.nonExistent().isEmpty()) {
                getLog().info(String.format(
                        "✓ [%s] All implementations properly registered (%d implementations)",
                        interfaceName,
                        result.implementations().size()
                ));
                continue;
            }

            boolean failMode = strategy == EnforcementStrategy.FAIL;

            if (failMode) {
                getLog().error("─────────────────────────────────────────────");
                getLog().error("Service Interface: " + interfaceName);
            } else {
                getLog().warn("─────────────────────────────────────────────");
                getLog().warn("Service Interface: " + interfaceName);
            }

            if (!result.unregistered().isEmpty()) {
                String header = String.format(
                        "✗ Unregistered implementations (%d):",
                        result.unregistered().size()
                );

                if (failMode) {
                    getLog().error(header);
                    result.unregistered().forEach(impl -> getLog().error("    " + impl));
                } else {
                    getLog().warn(header);
                    result.unregistered().forEach(impl -> getLog().warn("    " + impl));
                }
            }

            if (!result.nonExistent().isEmpty()) {
                getLog().warn(String.format(
                        "! Registered but non-existent implementations (%d):",
                        result.nonExistent().size()
                ));
                result.nonExistent().forEach(impl -> getLog().warn("    " + impl));
            }

            if (failMode) {
                getLog().error("─────────────────────────────────────────────");
            } else {
                getLog().warn("─────────────────────────────────────────────");
            }
        }
    }


    /**
     * Find all classes implementing the specified interface
     */
    private Set<String> findImplementations(Path classesDir, String serviceInterface) throws IOException {
        Set<String> implementations = new HashSet<>();
        
        try (Stream<Path> paths = Files.walk(classesDir)) {
            paths.filter(path -> path.toString().endsWith(CLASS_FILE_EXTENTION))
                 .filter(path -> !path.toString().contains(INNER_CLASS_PREFIX))
                 .forEach(classFile -> {
                     try {
                         String className = getClassName(classesDir, classFile);
                         
                         // Check package filter
                         if (shouldScanClass(className)) {
                             if (isValidImplementation(classFile, serviceInterface)) {
                                 implementations.add(className);
                             }
                         }
                     } catch (Exception e) {
                         getLog().debug("Error checking class: " + classFile + " - " + e.getMessage());
                     }
                 });
        }
        
        return implementations;
    }

    private boolean shouldScanClass(String className) {

        if (packagesToScan.isEmpty()) {
            return true;
        }
        
        return packagesToScan.stream()
                .anyMatch(className::startsWith);
    }

    private boolean isValidImplementation(Path classFile, String serviceInterface) {
        return implementsInterface(classFile, serviceInterface) && !isAbstractClass(classFile) && !isInterface(classFile);
    }

    /**
     * Check if a class file implements the specified interface using ASM
     */
    private boolean implementsInterface(Path classFile, String serviceInterface) {
        try (InputStream is = Files.newInputStream(classFile)) {
            ClassReader reader = new ClassReader(is);
            ClassNode classNode = new ClassNode();
            reader.accept(classNode, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            
            return checkClassImplementsInterface(classNode, serviceInterface, new HashSet<>());
            
        } catch (IOException e) {
            getLog().debug("Error analyzing class file: " + classFile+ " - " + e.getMessage());
            return false;
        }
    }

    private boolean checkClassImplementsInterface(ClassNode classNode, String serviceInterface, Set<String> visited) {
        if (visited.contains(classNode.name)) {
            return false;
        }
        visited.add(classNode.name);

        String internalInterfaceName = serviceInterface.replace('.', '/');

        if (classNode.interfaces != null && classNode.interfaces.contains(internalInterfaceName)) {
            return true;
        }

        if (classNode.superName != null && !classNode.superName.equals(OBJECT_CLASS)) {
            try {
                ClassNode superClass = loadClassNode(classNode.superName);
                if (superClass != null && checkClassImplementsInterface(superClass, serviceInterface, visited)) {
                    return true;
                }
            } catch (Exception e) {
                getLog().debug("Could not load superclass: " + classNode.superName + " - " + e.getMessage());
            }
        }

        if (classNode.interfaces != null) {
            for (String interfaceName : classNode.interfaces) {
                try {
                    ClassNode interfaceClass = loadClassNode(interfaceName);
                    if (interfaceClass != null && checkClassImplementsInterface(interfaceClass, serviceInterface, visited)) {
                        return true;
                    }
                } catch (Exception e) {
                    getLog().debug("Could not load interface: " + interfaceName + " - " + e.getMessage());
                }
            }
        }

        return false;
    }

    /**
     * Load a ClassNode from either the project's classes or the classpath
     */
    private ClassNode loadClassNode(String internalClassName) throws IOException {

        String outputDirectory = project.getBuild().getOutputDirectory();
        Path classFile = Paths.get(outputDirectory).resolve(internalClassName + CLASS_FILE_EXTENTION);

        if (Files.exists(classFile)) {
            try (InputStream is = Files.newInputStream(classFile)) {
                ClassReader reader = new ClassReader(is);
                ClassNode classNode = new ClassNode();
                reader.accept(classNode, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                return classNode;
            }
        }

        String className = internalClassName.replace('/', '.');
        try {
            InputStream is = getClass().getClassLoader().getResourceAsStream(internalClassName + CLASS_FILE_EXTENTION);
            if (is != null) {
                try (InputStream stream = is) {
                    ClassReader reader = new ClassReader(stream);
                    ClassNode classNode = new ClassNode();
                    reader.accept(classNode, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                    return classNode;
                }
            }
        } catch (Exception e) {
            getLog().debug("Could not load class from classpath: " + className + " - " + e.getMessage());
        }

        return null;
    }

    private boolean isAbstractClass(Path classFile) {
        try (InputStream is = Files.newInputStream(classFile)) {
            ClassReader reader = new ClassReader(is);
            ClassNode classNode = new ClassNode();
            reader.accept(classNode, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            
            return (classNode.access & Opcodes.ACC_ABSTRACT) != 0;
            
        } catch (IOException e) {
            return false;
        }
    }

    private boolean isInterface(Path classFile) {
        try (InputStream is = Files.newInputStream(classFile)) {
            ClassReader reader = new ClassReader(is);
            ClassNode classNode = new ClassNode();
            reader.accept(classNode, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            
            return (classNode.access & Opcodes.ACC_INTERFACE) != 0;
            
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Read registered services from META-INF/services file and module-info.class
     */
    private Set<String> readRegisteredServices(Path classesDir, String serviceInterface) throws IOException {
        Set<String> registered = new HashSet<>();


        Path servicesFile = classesDir.resolve("META-INF").resolve("services").resolve(serviceInterface);
        if (Files.exists(servicesFile)) {
            List<String> lines = Files.readAllLines(servicesFile);
            for (String line : lines) {
                line = line.trim();
                if (!line.isEmpty() && !line.startsWith("#")) {
                    registered.add(line);
                }
            }
        }


        Path moduleInfo = classesDir.resolve("module-info.class");
        if (Files.exists(moduleInfo)) {
            try (InputStream is = Files.newInputStream(moduleInfo)) {
                ClassReader reader = new ClassReader(is);
                ClassNode node = new ClassNode();
                reader.accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

                if (node.module != null) {
                    ModuleNode moduleNode = node.module;
                    if (moduleNode.provides != null) {
                        for (ModuleProvideNode provide : moduleNode.provides) {
                            String declaredInterface = provide.service.replace('/', '.');
                            if (declaredInterface.equals(serviceInterface)) {
                                for (String impl : provide.providers) {
                                    registered.add(impl.replace('/', '.'));
                                }
                            }
                        }
                    }
                }
            }
        }

        return registered;
    }

    /**
     * Convert file path to class name
     */
    private String getClassName(Path classesDir, Path classFile) {
        Path relativePath = classesDir.relativize(classFile);
        String className = relativePath.toString();
        className = className.substring(0, className.length() - 6); // Remove .class
        return className.replace(File.separator, ".");
    }

    public List<String> getServiceInterfaces() {
        return serviceInterfaces;
    }

    public void setServiceInterfaces(List<String> serviceInterfaces) {
        this.serviceInterfaces = serviceInterfaces != null ? serviceInterfaces : new ArrayList<>();
    }

    public List<String> getPackagesToScan() {
        return packagesToScan;
    }

    public void setPackagesToScan(List<String> packagesToScan) {
        this.packagesToScan = packagesToScan != null ? packagesToScan : new ArrayList<>();
    }

    public EnforcementStrategy getStrategy() {
        return strategy;
    }

    public void setStrategy(EnforcementStrategy strategy) {
        this.strategy = strategy != null ? strategy : EnforcementStrategy.FAIL;
    }

    public void setStrategy(String strategy) {
        if ("WARN".equalsIgnoreCase(strategy) || "WARNING".equalsIgnoreCase(strategy)) {
            this.strategy = EnforcementStrategy.WARN;
        } else if ("FAIL".equalsIgnoreCase(strategy) || "FAILURE".equalsIgnoreCase(strategy)) {
            this.strategy = EnforcementStrategy.FAIL;
        } else {
            throw new IllegalArgumentException("Invalid strategy: " + strategy + ". Valid values are: FAIL, WARN");
        }
    }

    /**
     * If your rule is cacheable, you must return a unique id when parameters or conditions
     * change that would cause the result to be different. Multiple cached results are stored
     * based on their id.
     * <p>
     * The easiest way to do this is to return a hash computed from the values of your parameters.
     * <p>
     * If your rule is not cacheable, then you don't need to override this method or return null
     */
    @Override
    public String getCacheId() {
        return null;
    }

    /**
     * A good practice is provided toString method for Enforcer Rule.
     * <p>
     * Output is used in verbose Maven logs, can help during investigate problems.
     *
     * @return rule description
     */
    @Override
    public String toString() {
        return String.format(
                "serviceRegistrationRule[serviceInterfaces=%s, packagesToScan=%s, strategy=%s]",
                serviceInterfaces,
                packagesToScan,
                strategy
        );
    }
}