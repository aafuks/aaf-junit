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

/**
 * @author Amihai Fuks
 * @version Dec 11, 2016
 * @since 1.0.0
 */
public class DependencyTree {

    private final Map<String, List<String>> onDepends;
    private final Map<String, List<String>> dependsOn;

    public DependencyTree() {
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
                throw new IllegalStateException("loop detected in dependency tree ('" + d + "')");
            }
            detectLoops(d, new HashSet<>(s));
        }
    }

    private void detectMissingOnDepends() {
        for (String k : onDepends.keySet()) {
            if (!dependsOn.containsKey(k)) {
                throw new IllegalStateException("'" + k + "' is being depended on but does not exist in dependency tree");
            }
        }
    }

    private synchronized List<String> next() {
        return dependsOn.entrySet().stream().filter(e -> e.getValue().isEmpty()).map(e -> e.getKey()).collect(Collectors.toList());
    }

    public synchronized List<String> next(String subject) {
        if (!onDepends.containsKey(subject)) {
            return Collections.emptyList();
        }
        onDepends.get(subject).forEach(d -> dependsOn.get(d).remove(subject));
        return next();
    }

}
