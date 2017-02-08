/*
 * Licensed under the MIT license (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    https://opensource.org/licenses/MIT
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.aaf.junit;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.runners.model.InitializationError;

/**
 * @author Amihai Fuks
 * @version Dec 11, 2016
 * @since 1.0.0
 */
public class DependencyGraph {

    private static final char NL = '\n';

    private final Map<String, Set<String>> onDepends;
    private final Map<String, Set<String>> dependsOn;

    public DependencyGraph() {
        onDepends = new HashMap<>();
        dependsOn = new HashMap<>();
    }

    public synchronized void addDependecy(String subject, String[] dependsOns) {
        dependsOn.put(subject, new HashSet<>(Arrays.asList(dependsOns)));
        for (String d : dependsOns) {
            onDepends.putIfAbsent(d, new HashSet<>());
            onDepends.get(d).add(subject);
        }
    }

    public synchronized void verify() throws InitializationError {
        detectMissingOnDepends();
        detectLoops();
    }

    private void detectLoops() throws InitializationError {
        for (String subject : dependsOn.keySet()) {
            detectLoops(subject, new HashSet<>());
        }
    }

    private void detectLoops(String root, Set<String> s) throws InitializationError {
        for (String d : dependsOn.get(root)) {
            if (!s.add(d)) {
                throw new InitializationError("loop detected in dependency graph ('" + d + "')");
            }
            detectLoops(d, new HashSet<>(s));
        }
    }

    private void detectMissingOnDepends() throws InitializationError {
        for (String k : onDepends.keySet()) {
            if (!dependsOn.containsKey(k)) {
                throw new InitializationError("'" + k + "' is being depended on but does not exist in dependency graph");
            }
        }
    }

    public synchronized List<String> getRoots() {
        return dependsOn.entrySet().stream().filter(e -> e.getValue().isEmpty()).map(e -> e.getKey()).collect(Collectors.toList());
    }

    public synchronized List<String> next(String node) {
        if (!onDepends.containsKey(node)) {
            return Collections.emptyList();
        }
        List<String> next = new ArrayList<>();
        onDepends.get(node).forEach(d -> {
            dependsOn.get(d).remove(node);
            if (dependsOn.get(d).isEmpty()) {
                next.add(d);
            }
        });
        return next;
    }

    public synchronized void clear() {
        dependsOn.clear();
        onDepends.clear();
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("depends on:").append(NL);
        dependsOn.entrySet().stream().forEach(e -> sb.append(e.getKey() + ": " + e.getValue() + NL));
        sb.append("on depends:").append(NL);
        onDepends.entrySet().stream().forEach(e -> sb.append(e.getKey() + ": " + e.getValue() + NL));
        return sb.toString();
    }

}
