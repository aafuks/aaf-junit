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

/**
 * @author Amihai Fuks
 * @version Feb 10, 2017
 * @since 1.0.0
 */
public class SystemPropertyEnabledWithCallback implements EnabledWithCallback {

    @Override
    public boolean eval(String[] params) {
        for (String param : params) {
            int equalIdx = param.indexOf('=');
            if (equalIdx == -1 || equalIdx == param.length() - 1) {
                String key = param.substring(0, equalIdx != -1 ? equalIdx : param.length());
                if (System.getProperty(key) == null) {
                    return false;
                }
            } else {
                String key = param.substring(0, equalIdx);
                String value = param.substring(equalIdx + 1);
                if (!value.equalsIgnoreCase(System.getProperty(key))) {
                    return false;
                }
            }
        }
        return true;
    }

}
