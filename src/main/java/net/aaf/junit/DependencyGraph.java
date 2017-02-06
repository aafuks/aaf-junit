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

/**
 * @author Amihai Fuks
 * @version Dec 11, 2016
 * @since 1.0.0
 */
public class DependencyGraph {

    private static final char NL = '\n';

    private final Map<String, List<String>> onDepends;
    private final Map<String, List<String>> dependsOn;

    public DependencyGraph() {
        onDepends = new HashMap<>();
        dependsOn = new HashMap<>();
    }

    public synchronized void addDependecy(String subject, String[] dependsOns) {
        dependsOn.put(subject, new ArrayList<>(Arrays.asList(dependsOns)));
        for (String d : dependsOns) {
            if (!onDepends.containsKey(d)) {
                onDepends.put(d, new ArrayList<>());
            }
            onDepends.get(d).add(subject);
        }
    }

    public synchronized void verify() {
        detectMissingOnDepends();
        detectLoops();
    }

    private void detectLoops() {
        for (String subject : dependsOn.keySet()) {
            detectLoops(subject, new HashSet<>());
        }
    }

    private void detectLoops(String root, Set<String> s) {
        for (String d : dependsOn.get(root)) {
            if (!s.add(d)) {
                throw new IllegalStateException("loop detected in dependency graph ('" + d + "')");
            }
            detectLoops(d, new HashSet<>(s));
        }
    }

    private void detectMissingOnDepends() {
        for (String k : onDepends.keySet()) {
            if (!dependsOn.containsKey(k)) {
                throw new IllegalStateException("'" + k + "' is being depended on but does not exist in dependency graph");
            }
        }
    }

    public synchronized List<String> next(String subject) {
        if (!onDepends.containsKey(subject)) {
            return Collections.emptyList();
        }
        List<String> next = new ArrayList<>();
        onDepends.get(subject).forEach(d -> {
            dependsOn.get(d).remove(subject);
            if (dependsOn.get(d).isEmpty()) {
                next.add(d);
            }
        });
        return next;
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
