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

                if (!result.unregistered().isEmpty()) {
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

        getLog().debug("=== Checking service interface: " + serviceInterface + " ===");

        Set<String> implementations = findImplementations(classesDir, serviceInterface);
        getLog().debug("Found " + implementations.size() + " implementations: " + implementations);

        Set<String> registeredServices = readRegisteredServices(classesDir, serviceInterface);
        getLog().debug("Found " + registeredServices.size() + " registered services: " + registeredServices);

        Set<String> unregistered = new HashSet<>(implementations);
        unregistered.removeAll(registeredServices);
        getLog().debug("Unregistered implementations: " + unregistered);

        Set<String> nonExistent = new HashSet<>(registeredServices);
        nonExistent.removeAll(implementations);
        getLog().debug("Non-existent registrations: " + nonExistent);

        return new ServiceCheckResult(serviceInterface, implementations, registeredServices, unregistered, nonExistent);
    }

    /**
     * Find all classes implementing the specified interface
     */
    private Set<String> findImplementations(Path classesDir, String serviceInterface) throws IOException {
        getLog().debug("Looking for implementations of " + serviceInterface + " in " + classesDir);

        Set<String> candidateImplementations = new HashSet<>();
        Map<String, ClassNode> classNodes = new HashMap<>();

        // First pass: collect all class nodes
        try (Stream<Path> paths = Files.walk(classesDir)) {
            paths.filter(path -> path.toString().endsWith(CLASS_FILE_EXTENTION))
                    .filter(path -> !path.toString().contains(INNER_CLASS_PREFIX))
                    .forEach(classFile -> {
                        try {
                            String className = getClassName(classesDir, classFile);
                            getLog().debug("Found class file: " + classFile + " -> " + className);

                            if (shouldScanClass(className)) {
                                getLog().debug("Scanning class: " + className);
                                try (InputStream is = Files.newInputStream(classFile)) {
                                    ClassReader reader = new ClassReader(is);
                                    ClassNode classNode = new ClassNode();
                                    reader.accept(classNode, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                                    classNodes.put(classNode.name, classNode);
                                    getLog().debug("Loaded ClassNode: " + classNode.name +
                                            ", access=" + classNode.access +
                                            ", superName=" + classNode.superName +
                                            ", interfaces=" + classNode.interfaces);
                                }
                            } else {
                                getLog().debug("Skipping class (not in scan packages): " + className);
                            }
                        } catch (Exception e) {
                            getLog().error("Error reading class file: " + classFile + " - " + e.getMessage());
                        }
                    });
        }

        getLog().debug("Collected " + classNodes.size() + " class nodes");

        // Second pass: find all concrete classes implementing/extending serviceInterface
        for (ClassNode node : classNodes.values()) {
            String className = node.name.replace('/', '.');
            getLog().debug("Checking node: " + className +
                    ", isInterface=" + ((node.access & Opcodes.ACC_INTERFACE) != 0) +
                    ", isAbstract=" + ((node.access & Opcodes.ACC_ABSTRACT) != 0));

            if ((node.access & Opcodes.ACC_INTERFACE) != 0 || (node.access & Opcodes.ACC_ABSTRACT) != 0) {
                getLog().debug("Skipping " + className + " (interface or abstract)");
                continue; // skip interfaces and abstract classes
            }

            getLog().debug("Checking if " + className + " implements/extends " + serviceInterface);
            if (checkClassImplementsInterfaceOrExtendsClass(node, serviceInterface, classNodes, new HashSet<>())) {
                getLog().debug("✓ " + className + " IS an implementation of " + serviceInterface);
                candidateImplementations.add(className);
            } else {
                getLog().debug("✗ " + className + " is NOT an implementation of " + serviceInterface);
            }
        }

        getLog().debug("Final implementations found: " + candidateImplementations);
        return candidateImplementations;
    }

    private boolean checkClassImplementsInterfaceOrExtendsClass(ClassNode classNode, String serviceInterface, Map<String, ClassNode> classNodes, Set<String> visited) {
        if (classNode == null) {
            getLog().debug("  ClassNode is null, returning false");
            return false;
        }

        if (visited.contains(classNode.name)) {
            getLog().debug("  Already visited " + classNode.name + ", returning false to avoid cycle");
            return false; // Avoid cycles
        }
        visited.add(classNode.name);

        String internalServiceName = serviceInterface.replace('.', '/');
        String currentClassName = classNode.name.replace('/', '.');

        getLog().debug("  Checking " + currentClassName + " against " + serviceInterface);
        getLog().debug("    classNode.name=" + classNode.name);
        getLog().debug("    internalServiceName=" + internalServiceName);
        getLog().debug("    superName=" + classNode.superName);
        getLog().debug("    interfaces=" + classNode.interfaces);

        // Direct match - check if this class IS the service interface/class we're looking for
        if (classNode.name.equals(internalServiceName)) {
            getLog().debug("  ✓ Direct match: " + currentClassName + " IS " + serviceInterface);
            return true;
        }

        // Check implemented interfaces
        if (classNode.interfaces != null) {
            for (String interfaceName : classNode.interfaces) {
                getLog().debug("  Checking interface: " + interfaceName);
                if (interfaceName.equals(internalServiceName)) {
                    getLog().debug("  ✓ Interface match: " + currentClassName + " implements " + serviceInterface);
                    return true;
                }
                // Try to load from collected classNodes first, then fallback to loadClassNode
                ClassNode ifaceNode = classNodes.get(interfaceName);
                if (ifaceNode == null) {
                    getLog().debug("  Interface " + interfaceName + " not in classNodes, trying loadClassNode");
                    ifaceNode = loadClassNode(interfaceName);
                } else {
                    getLog().debug("  Interface " + interfaceName + " found in classNodes");
                }
                if (ifaceNode != null && checkClassImplementsInterfaceOrExtendsClass(ifaceNode, serviceInterface, classNodes, visited)) {
                    getLog().debug("  ✓ Inheritance match via interface: " + currentClassName + " -> " + interfaceName.replace('/', '.') + " -> " + serviceInterface);
                    return true;
                }
            }
        }

        // Check superclass recursively
        if (classNode.superName != null && !classNode.superName.equals(OBJECT_CLASS)) {
            getLog().debug("  Checking superclass: " + classNode.superName);
            // Try to load from collected classNodes first, then fallback to loadClassNode
            ClassNode superNode = classNodes.get(classNode.superName);
            if (superNode == null) {
                getLog().debug("  Superclass " + classNode.superName + " not in classNodes, trying loadClassNode");
                superNode = loadClassNode(classNode.superName);
            } else {
                getLog().debug("  Superclass " + classNode.superName + " found in classNodes");
            }
            if (superNode != null && checkClassImplementsInterfaceOrExtendsClass(superNode, serviceInterface, classNodes, visited)) {
                getLog().debug("  ✓ Inheritance match via superclass: " + currentClassName + " -> " + classNode.superName.replace('/', '.') + " -> " + serviceInterface);
                return true;
            }
        }

        getLog().debug("  ✗ No match found for " + currentClassName);
        return false;
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

            if (failMode && !result.unregistered().isEmpty()) {
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

            if (failMode && !result.unregistered().isEmpty()) {
                getLog().error("─────────────────────────────────────────────");
            } else {
                getLog().warn("─────────────────────────────────────────────");
            }
        }
    }

    private boolean shouldScanClass(String className) {

        if (packagesToScan.isEmpty()) {
            return true;
        }

        return packagesToScan.stream()
                .anyMatch(className::startsWith);
    }

    /**
     * Load a ClassNode from either the project's classes or the classpath
     */
    private ClassNode loadClassNode(String internalClassName) {
        try {
            String outputDirectory = project.getBuild().getOutputDirectory();

            // Convert internal class name to path
            String classFilePath = internalClassName.replace('/', File.separatorChar) + CLASS_FILE_EXTENTION;
            Path classFile = Paths.get(outputDirectory).resolve(classFilePath);

            if (Files.exists(classFile)) {
                try (InputStream is = Files.newInputStream(classFile)) {
                    ClassReader reader = new ClassReader(is);
                    ClassNode classNode = new ClassNode();
                    reader.accept(classNode, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                    return classNode;
                }
            }

            // Fallback: try classpath
            InputStream is = getClass().getClassLoader().getResourceAsStream(internalClassName + CLASS_FILE_EXTENTION);
            if (is != null) {
                try (InputStream stream = is) {
                    ClassReader reader = new ClassReader(stream);
                    ClassNode classNode = new ClassNode();
                    reader.accept(classNode, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                    return classNode;
                }
            }

            getLog().debug("Could not load class (not found): " + internalClassName);

        } catch (Exception e) {
            getLog().debug("Could not load class: " + internalClassName + " - " + e.getMessage());
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