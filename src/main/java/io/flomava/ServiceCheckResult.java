/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.flomava;

import java.util.Set;

public record ServiceCheckResult(String serviceInterface, Set<String> implementations, Set<String> registeredServices, Set<String> unregistered,
                          Set<String> nonExistent) {
}
