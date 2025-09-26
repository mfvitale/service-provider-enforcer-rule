package io.flomava;

import org.apache.maven.enforcer.rule.api.EnforcerLogger;
import org.apache.maven.enforcer.rule.api.EnforcerRuleException;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Build;
import org.apache.maven.project.MavenProject;
import org.apache.maven.rtinfo.RuntimeInformation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.ModuleVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

public class ServiceRegistrationRuleTest {

    @TempDir
    Path tempDir;

    @Mock
    private MavenProject project;

    @Mock
    private MavenSession session;

    @Mock
    private RuntimeInformation runtimeInformation;

    @Mock
    private Build build;

    @Mock
    private EnforcerLogger log;

    private ServiceRegistrationRule rule;
    private Path classesDir;
    private Path servicesDir;

    @BeforeEach
    void setUp() throws IOException {
        MockitoAnnotations.openMocks(this);

        classesDir = tempDir.resolve("classes");
        servicesDir = classesDir.resolve("META-INF").resolve("services");
        Files.createDirectories(servicesDir);

        when(project.getBuild()).thenReturn(build);
        when(build.getOutputDirectory()).thenReturn(classesDir.toString());

        rule = new ServiceRegistrationRule(project, session, runtimeInformation);

        // Spy the rule so we can stub getLog()
        rule = spy(rule);
        when(rule.getLog()).thenReturn(log);
    }

    @Test
    void testExecute_NoServiceInterfacesConfigured_ThrowsException() {
        rule.setServiceInterfaces(Collections.emptyList());

        EnforcerRuleException exception = assertThrows(EnforcerRuleException.class,
                () -> rule.execute());

        assertTrue(exception.getMessage().contains("serviceInterfaces parameter is required"));
    }

    @Test
    void testExecute_NullServiceInterfaces_ThrowsException() {
        rule.setServiceInterfaces(null);

        EnforcerRuleException exception = assertThrows(EnforcerRuleException.class,
                () -> rule.execute());

        assertTrue(exception.getMessage().contains("serviceInterfaces parameter is required"));
    }

    @Test
    void testExecute_AllImplementationsRegistered_Success() throws Exception {

        String serviceInterface = "com.example.TestService";
        String implementation = "com.example.TestServiceImpl";

        createTestInterface(serviceInterface);
        createTestImplementation(implementation, serviceInterface);
        createServiceRegistration(serviceInterface, implementation);

        rule.setServiceInterfaces(Arrays.asList(serviceInterface));

        assertDoesNotThrow(() -> rule.execute());
    }

    @Test
    void testExecute_UnregisteredImplementation_FailStrategy_ThrowsException() throws Exception {
        String serviceInterface = "com.example.TestService";
        String implementation = "com.example.TestServiceImpl";

        createTestInterface(serviceInterface);
        createTestImplementation(implementation, serviceInterface);

        rule.setServiceInterfaces(Arrays.asList(serviceInterface));
        rule.setStrategy(ServiceRegistrationRule.EnforcementStrategy.FAIL);

        EnforcerRuleException exception = assertThrows(EnforcerRuleException.class,
                () -> rule.execute());

        assertTrue(exception.getMessage().contains("Service registration violations found"));
    }

    @Test
    void testExecute_UnregisteredImplementation_WarnStrategy_Success() throws Exception {
        String serviceInterface = "com.example.TestService";
        String implementation = "com.example.TestServiceImpl";

        createTestInterface(serviceInterface);
        createTestImplementation(implementation, serviceInterface);

        rule.setServiceInterfaces(Arrays.asList(serviceInterface));
        rule.setStrategy(ServiceRegistrationRule.EnforcementStrategy.WARN);

        assertDoesNotThrow(() -> rule.execute());
    }

    @Test
    void testExecute_MultipleInterfaces() throws Exception {
        String serviceInterface1 = "com.example.Service1";
        String serviceInterface2 = "com.example.Service2";
        String impl1 = "com.example.Service1Impl";
        String impl2 = "com.example.Service2Impl";

        createTestInterface(serviceInterface1);
        createTestInterface(serviceInterface2);
        createTestImplementation(impl1, serviceInterface1);
        createTestImplementation(impl2, serviceInterface2);

        createServiceRegistration(serviceInterface1, impl1);

        rule.setServiceInterfaces(Arrays.asList(serviceInterface1, serviceInterface2));
        rule.setStrategy(ServiceRegistrationRule.EnforcementStrategy.FAIL);

        EnforcerRuleException exception = assertThrows(EnforcerRuleException.class,
                () -> rule.execute());

        assertTrue(exception.getMessage().contains("Service registration violations found"));
    }

    @Test
    void testExecute_NonExistentRegisteredService() throws Exception {
        String serviceInterface = "com.example.TestService";
        String nonExistentImpl = "com.example.NonExistentImpl";

        createTestInterface(serviceInterface);
        createServiceRegistration(serviceInterface, nonExistentImpl);

        rule.setServiceInterfaces(Arrays.asList(serviceInterface));
        rule.setStrategy(ServiceRegistrationRule.EnforcementStrategy.WARN);

        assertDoesNotThrow(() -> rule.execute());
    }

    @Test
    void testExecute_PackageFiltering() throws Exception {
        String serviceInterface = "com.example.TestService";
        String impl1 = "com.example.allowed.TestServiceImpl1";
        String impl2 = "com.example.forbidden.TestServiceImpl2";

        createTestInterface(serviceInterface);
        createTestImplementation(impl1, serviceInterface);
        createTestImplementation(impl2, serviceInterface);
        createServiceRegistration(serviceInterface, impl1);

        rule.setServiceInterfaces(Arrays.asList(serviceInterface));
        rule.setPackagesToScan(Arrays.asList("com.example.allowed"));
        rule.setStrategy(ServiceRegistrationRule.EnforcementStrategy.FAIL);

        // Should succeed because we only scan allowed package
        assertDoesNotThrow(() -> rule.execute());
    }

    @Test
    void testExecute_AbstractClassHandling() throws Exception {
        String serviceInterface = "com.example.TestService";
        String abstractImpl = "com.example.AbstractTestServiceImpl";
        String concreteImpl = "com.example.ConcreteTestServiceImpl";

        createTestInterface(serviceInterface);
        createAbstractTestImplementation(abstractImpl, serviceInterface);
        createTestImplementation(concreteImpl, serviceInterface);
        createServiceRegistration(serviceInterface, concreteImpl);

        rule.setServiceInterfaces(Arrays.asList(serviceInterface));
        rule.setStrategy(ServiceRegistrationRule.EnforcementStrategy.FAIL);

        // Should succeed because abstract class is ignored
        assertDoesNotThrow(() -> rule.execute());
    }

    @Test
    void testExecute_ServiceRegistrationWithComments() throws Exception {
        String serviceInterface = "com.example.TestService";
        String implementation = "com.example.TestServiceImpl";

        createTestInterface(serviceInterface);
        createTestImplementation(implementation, serviceInterface);

        // Create service registration with comments and empty lines
        Path serviceFile = servicesDir.resolve(serviceInterface);
        String content = "# This is a comment\n" +
                "\n" +
                implementation + "\n" +
                "# Another comment\n" +
                "\n";
        Files.write(serviceFile, content.getBytes());

        rule.setServiceInterfaces(Arrays.asList(serviceInterface));

        assertDoesNotThrow(() -> rule.execute());
    }

    @Test
    void testExecute_AbstractServiceWithInheritanceChain() throws Exception {
        String abstractService = "com.example.AbstractTestService";
        String intermediateClass = "com.example.BaseTestServiceImpl";
        String concreteImpl = "com.example.ConcreteTestServiceImpl";

        // Create abstract service class
        createAbstractServiceClass(abstractService);

        // Create intermediate class that extends the abstract service
        createConcreteImplementation(intermediateClass, abstractService);

        // Create concrete implementation that extends the intermediate class
        createConcreteImplementation(concreteImpl, intermediateClass);

        // Register the concrete implementation
        createServiceRegistration(abstractService, concreteImpl);

        rule.setServiceInterfaces(Arrays.asList(abstractService));
        rule.setStrategy(ServiceRegistrationRule.EnforcementStrategy.FAIL);

        // Should succeed because the concrete implementation inherits from the abstract service
        // through the inheritance chain: ConcreteImpl -> BaseImpl -> AbstractService
        assertDoesNotThrow(() -> rule.execute());
    }

    @Test
    void testExecute_AbstractServiceWithUnregisteredInheritanceChain() throws Exception {
        String abstractService = "com.example.AbstractTestService";
        String intermediateClass = "com.example.BaseTestServiceImpl";
        String concreteImpl = "com.example.ConcreteTestServiceImpl";

        // Create abstract service class
        createAbstractServiceClass(abstractService);

        // Create intermediate class that extends the abstract service
        createConcreteImplementation(intermediateClass, abstractService);

        // Create concrete implementation that extends the intermediate class
        createConcreteImplementation(concreteImpl, intermediateClass);

        // Don't register the concrete implementation (this should cause failure)

        rule.setServiceInterfaces(Arrays.asList(abstractService));
        rule.setStrategy(ServiceRegistrationRule.EnforcementStrategy.FAIL);

        // Should fail because the concrete implementation is not registered
        EnforcerRuleException exception = assertThrows(EnforcerRuleException.class,
                () -> rule.execute());

        assertTrue(exception.getMessage().contains("Service registration violations found"));
    }

    private void createAbstractServiceClass(String className) throws IOException {
        Path classFile = createClassFile(className);

        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        String internalName = className.replace('.', '/');

        cw.visit(Opcodes.V11,
                Opcodes.ACC_PUBLIC + Opcodes.ACC_ABSTRACT,
                internalName,
                null,
                "java/lang/Object",
                null);

        // Add default constructor
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        mv.visitInsn(Opcodes.RETURN);
        // when using COMPUTE_MAXS we can pass 0,0
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        cw.visitEnd();
        Files.write(classFile, cw.toByteArray());
    }

    private void createConcreteImplementation(String className, String parentClassName) throws IOException {
        Path classFile = createClassFile(className);

        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        String internalName = className.replace('.', '/');
        String internalParentName = parentClassName.replace('.', '/');

        cw.visit(Opcodes.V11,
                Opcodes.ACC_PUBLIC,
                internalName,
                null,
                internalParentName,  // Extends the parent class
                null);

        // Add default constructor
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, internalParentName, "<init>", "()V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        cw.visitEnd();
        Files.write(classFile, cw.toByteArray());
    }

    private void createTestInterface(String interfaceName) throws IOException {
        Path interfaceFile = createClassFile(interfaceName);

        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        String internalName = interfaceName.replace('.', '/');

        cw.visit(Opcodes.V11,
                Opcodes.ACC_PUBLIC + Opcodes.ACC_INTERFACE + Opcodes.ACC_ABSTRACT,
                internalName,
                null,
                "java/lang/Object",
                null);

        cw.visitEnd();
        Files.write(interfaceFile, cw.toByteArray());
    }

    private void createTestImplementation(String className, String interfaceName) throws IOException {
        Path classFile = createClassFile(className);

        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        String internalName = className.replace('.', '/');
        String internalInterfaceName = interfaceName.replace('.', '/');

        cw.visit(Opcodes.V11,
                Opcodes.ACC_PUBLIC,
                internalName,
                null,
                "java/lang/Object",
                new String[]{internalInterfaceName});

        // Add default constructor
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        cw.visitEnd();
        Files.write(classFile, cw.toByteArray());
    }

    private void createAbstractTestImplementation(String className, String interfaceName) throws IOException {
        Path classFile = createClassFile(className);

        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        String internalName = className.replace('.', '/');
        String internalInterfaceName = interfaceName.replace('.', '/');

        cw.visit(Opcodes.V11,
                Opcodes.ACC_PUBLIC + Opcodes.ACC_ABSTRACT,
                internalName,
                null,
                "java/lang/Object",
                new String[]{internalInterfaceName});

        // Add default constructor
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        cw.visitEnd();
        Files.write(classFile, cw.toByteArray());
    }

    /**
     * Create the class file under classesDir using path segments so that the produced file
     * matches the real Java compiler layout (e.g. classes/com/example/Foo.class)
     */
    private Path createClassFile(String className) throws IOException {
        // Build file path segments from the FQN
        String[] parts = className.split("\\.");
        Path path = classesDir;
        for (int i = 0; i < parts.length - 1; i++) {
            path = path.resolve(parts[i]);
        }
        // ensure directories exist
        Files.createDirectories(path);
        Path classFile = path.resolve(parts[parts.length - 1] + ".class");
        return classFile;
    }

    private void createServiceRegistration(String serviceInterface, String... implementations) throws IOException {
        Path serviceFile = servicesDir.resolve(serviceInterface);
        StringBuilder content = new StringBuilder();
        for (String impl : implementations) {
            content.append(impl).append("\n");
        }
        Files.write(serviceFile, content.toString().getBytes());
    }

    @Test
    void testExecute_ServiceRegisteredViaModuleInfo() throws Exception {
        String serviceInterface = "com.example.TestService";
        String implementation = "com.example.TestServiceImpl";

        // Create interface and implementation
        createTestInterface(serviceInterface);
        createTestImplementation(implementation, serviceInterface);

        // Create module-info.class with "provides ... with ..."
        createModuleInfoWithProvides("com.example.module", serviceInterface, implementation);

        rule.setServiceInterfaces(Collections.singletonList(serviceInterface));
        rule.setStrategy(ServiceRegistrationRule.EnforcementStrategy.FAIL);

        // Should succeed because registration is via module-info
        assertDoesNotThrow(() -> rule.execute());
    }

    private void createModuleInfoWithProvides(String moduleName, String serviceInterface, String implementation) throws IOException {
        Path moduleFile = classesDir.resolve("module-info.class");

        ClassWriter cw = new ClassWriter(0);
        // Note: building module-info via ASM is a bit unusual; this creates a minimal module info with a 'provides'
        cw.visit(Opcodes.V11, Opcodes.ACC_MODULE, "module-info", null, null, null);

        ModuleVisitor mv = cw.visitModule(moduleName, Opcodes.ACC_OPEN, null);
        // ASM expects internal names in provides call
        mv.visitProvide(serviceInterface.replace('.', '/'), new String[]{implementation.replace('.', '/')});
        mv.visitEnd();

        cw.visitEnd();
        Files.write(moduleFile, cw.toByteArray());
    }
}