# Service Registration Enforcer Rule

A Maven Enforcer Rule to verify that all implementations of specified Java interfaces are properly registered as services. This rule supports both traditional `META-INF/services` SPI files and Java 9+ module system `provides ... with ...` declarations.

It helps ensure that service implementations are discoverable at runtime and prevents missing registrations from breaking your application.

---

## Features

- Verifies that implementations of specified interfaces are registered either via:
  - `META-INF/services/<interface>`
  - `module-info.class` with `provides ... with ...`
- Supports package filtering to scan only selected packages.
- Supports two enforcement strategies:
  - `FAIL` – Fail the build if unregistered implementations are found.
  - `WARN` – Log a warning but do not fail the build.
- Handles:
  - Unregistered implementations
  - Non-existent registered implementations
  - Service files with comments and empty lines

---

## Usage

Add the enforcer rule to your Maven `pom.xml`:

```xml
<build>
  <plugins>
    <plugin>
      <groupId>org.apache.maven.plugins</groupId>
      <artifactId>maven-enforcer-plugin</artifactId>
      <version>3.6.1</version>
      <dependencies>
        <dependency>
          <groupId>io.flomava</groupId>
          <artifactId>service-provider-enforcer-rule</artifactId>
          <version>0.1.0</version>
        </dependency>
      </dependencies>
      <executions>
        <execution>
          <id>verify-service-registration</id>
          <phase>verify</phase>
          <goals>
            <goal>enforce</goal>
          </goals>
          <configuration>
            <rules>
              <serviceRegistrationRule implementation="io.flomava.ServiceRegistrationRule">
                <serviceInterfaces>
                  <param>com.example.MyService</param>
                  <param>com.example.OtherService</param>
                </serviceInterfaces>
                <packagesToScan>
                  <param>com.example.impl</param>
                </packagesToScan>
                <strategy>FAIL</strategy>
              </serviceRegistrationRule>
            </rules>
          </configuration>
        </execution>
      </executions>
    </plugin>
  </plugins>
</build>
```

---

## Parameters

| Parameter              | Type              | Default        | Description |
|------------------------|-----------------|----------------|------------|
| `serviceInterfaces`    | List<String>     | Required       | List of fully qualified interface names whose implementations must be registered. |
| `packagesToScan`       | List<String>     | [] (all)       | List of packages to scan for implementations. Empty means scan all packages. |
| `strategy`             | FAIL / WARN      | FAIL           | Enforcement strategy. FAIL fails the build; WARN logs warnings only. |
---

## Behavior

1. Scanning
  - Scans compiled classes from the project’s output directory.
  - Applies package filters if specified.
  - Detects classes implementing the specified interfaces.

2. Validation
  - Reads registrations from:
    - `META-INF/services/<interface>`
    - `module-info.class` `provides ... with ...` declarations
  - Compares found implementations with registered services.
  - Collects:
    - Unregistered implementations (present in code but missing from registration)
    - Non-existent registered implementations (listed in registration but missing in code)

3. Reporting & Enforcement
  - Logs detailed results to Maven output.
  - Applies the selected strategy (FAIL or WARN).

---

## Notes

- Supports Java 8+ for SPI files and Java 9+ for module-based services.
- Skips inner classes automatically.
- Can handle comments and blank lines in `META-INF/services` files.
- Currently, classes from dependencies (or other modules in a multi-module project) are not checked.
