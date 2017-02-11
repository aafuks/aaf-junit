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
import java.util.TreeSet;
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

    private final Map<String, Set<String>> lastDependsOn;

    private final Map<String, Set<String>> onSynchronized;
    private final Map<String, String> synchronizedOn;
    private final Map<String, TreeSet<String>> synchronizedQueues;

    public DependencyGraph() {
        onDepends = new HashMap<>();
        dependsOn = new HashMap<>();

        lastDependsOn = new HashMap<>();

        onSynchronized = new HashMap<>();
        synchronizedOn = new HashMap<>();
        synchronizedQueues = new HashMap<>();
    }

    public synchronized void addDependecy(String subject, String[] dependsOns) {
        addDependecy(subject, dependsOns, false);
    }

    public synchronized void addDependecy(String subject, String[] dependsOns, boolean last) {
        onDepends.putIfAbsent(subject, new HashSet<>());
        dependsOn.put(subject, new HashSet<>(Arrays.asList(dependsOns)));
        for (String d : dependsOns) {
            onDepends.putIfAbsent(d, new HashSet<>());
            onDepends.get(d).add(subject);
        }
        if (last) {
            lastDependsOn.put(subject, new HashSet<>());
        }
    }

    public synchronized void addSynchronized(String subject, String key) {
        onSynchronized.putIfAbsent(key, new HashSet<>());
        onSynchronized.get(key).add(subject);
        synchronizedOn.put(subject, key);
    }

    public synchronized void verify() throws InitializationError {
        computeLastDependencies();
        detectLastDependenciesLoops();
        detectMissingOnDepends();
        detectLoops();
    }

    private void detectLastDependenciesLoops() throws InitializationError {
        for (Set<String> nodes : lastDependsOn.values()) {
            for (String n : nodes) {
                if (lastDependsOn.containsKey(n)) {
                    throw new InitializationError("multi last dependecny detected ('" + n + "')");
                }
            }
        }
    }

    private void detectLoops() throws InitializationError {
        for (String subject : dependsOn.keySet()) {
            detectLoops(subject, new HashSet<>());
        }
    }

    private void detectLoops(String root, Set<String> s) throws InitializationError {
        for (String d : dependsOn.get(root)) {
            if (!s.add(d)) {
                throw new InitializationError("loop/multi-path detected in dependency graph ('" + d + "')");
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
        computeLastDependencies();
        return filterSychronizedOn(dependsOn.entrySet().stream().filter(e -> e.getValue().isEmpty()).map(e -> e.getKey())
                .collect(Collectors.toList()));
    }

    private void computeLastDependencies() {
        lastDependsOn.keySet().stream().forEach(n -> getSubDependencyGraph(n));
    }

    private void getSubDependencyGraph(String subject) {
        dependsOn.get(subject).forEach(n -> lastDependsOn.get(subject).addAll(getSubDependencyGraph(subject, n)));
    }

    private Set<String> getSubDependencyGraph(String subject, String node) {
        Set<String> leafs = new HashSet<>();
        if (subject == node) {
            return leafs;
        }
        if (onDepends.get(node).isEmpty()) {
            leafs.add(node);
            return leafs;
        }
        leafs.add(node);
        onDepends.get(node).forEach(n -> leafs.addAll(getSubDependencyGraph(subject, n)));
        return leafs;
    }

    private List<String> filterSychronizedOn(List<String> nodes) {
        return nodes.stream().filter(n -> !(synchronizedOn.containsKey(n) && shouldWait(n))).collect(Collectors.toList());
    }

    private boolean shouldWait(String node) {
        String key = synchronizedOn.get(node);
        synchronizedQueues.putIfAbsent(key, new TreeSet<>());
        synchronizedQueues.get(key).add(node);
        return synchronizedQueues.get(key).iterator().next() != node;
    }

    public synchronized List<String> next(String node) {
        if (!onDepends.containsKey(node)) {
            return Collections.emptyList(); // there are multiple listeners in a suite that each gets all messages
        }
        List<String> next = new ArrayList<>();
        addOnDepends(node, next);
        addLastDependsOn(node, next);
        addSynchronized(node, next);
        return filterSychronizedOn(next);
    }

    private void addLastDependsOn(String node, List<String> next) {
        lastDependsOn.remove(node);
        lastDependsOn.keySet().forEach(n -> lastDependsOn.get(n).remove(node));
        lastDependsOn.entrySet().stream().filter(e -> e.getValue().isEmpty()).forEach(e -> next.add(e.getKey()));
    }

    private void addSynchronized(String node, List<String> next) {
        if (!synchronizedOn.containsKey(node)) {
            return;
        }
        String key = synchronizedOn.get(node);
        synchronizedQueues.get(key).remove(node);
        onSynchronized.get(key).remove(node);
        onSynchronized.get(key).stream().forEach(n -> next.add(n));
    }

    private void addOnDepends(String node, List<String> next) {
        onDepends.get(node).forEach(d -> {
            dependsOn.get(d).remove(node);
            if (dependsOn.get(d).isEmpty() && (!lastDependsOn.containsKey(d) || lastDependsOn.get(d).isEmpty())) {
                next.add(d);
            }
        });
    }

    public synchronized void clear() {
        dependsOn.clear();
        onDepends.clear();

        lastDependsOn.clear();

        onSynchronized.clear();
        synchronizedOn.clear();
        synchronizedQueues.clear();
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
