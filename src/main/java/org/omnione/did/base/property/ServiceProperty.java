/*
 * Copyright 2024 OmniOne.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.omnione.did.base.property;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Map;

@Getter
@Setter
@ConfigurationProperties(prefix = "services")
public class ServiceProperty {
    private Map<String, ServiceDetail> server;
    public String jarPath;
    public ServiceProperty(){}

//    public ServiceProperty(Map<String, ServiceDetail> services) {
//        this.services = services;
//    }

    @Getter
    @Setter
    public static class ServiceDetail {
        private String name;
        private int port;
        private String file;
    }
}