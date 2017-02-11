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

import java.util.Arrays;

/**
 * @author Amihai Fuks
 * @version Feb 10, 2017
 * @since 1.0.0
 */
public class SystemPropertyEnabledWithCallback implements EnabledWithCallback {

    @Override
    public boolean eval(String[] params) {
        return Arrays.stream(params).allMatch(p -> eval(p));
    }

    private static boolean eval(String param) {
        if (!param.contains("=")) {
            return System.getProperty(param) != null;
        } else if (param.charAt(param.length() - 1) == '=') {
            String key = param.substring(0, param.length());
            return System.getProperty(key) != null;
        } else {
            String[] split = param.split("=");
            return equalsIgnoreCase(split[0], split[1]);
        }
    }

    private static boolean equalsIgnoreCase(String key, String value) {
        return value.equalsIgnoreCase(System.getProperty(key));
    }

}
