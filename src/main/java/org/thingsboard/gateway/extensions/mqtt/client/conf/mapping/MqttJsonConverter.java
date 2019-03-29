/**
 * Copyright © 2017 The Thingsboard Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.thingsboard.gateway.extensions.mqtt.client.conf.mapping;

import com.fasterxml.jackson.databind.JsonNode;
import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.Option;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.springframework.util.StringUtils;
import org.thingsboard.gateway.service.data.DeviceData;
import org.thingsboard.gateway.util.converter.BasicJsonConverter;
import org.thingsboard.server.common.data.kv.*;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import java.text.ParseException;

/**
 * Created by ashvayka on 23.01.17.
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Slf4j
public class MqttJsonConverter extends BasicJsonConverter implements MqttDataConverter {

    private String deviceNameTopicExpression;
    private Pattern deviceNameTopicPattern;

    private String deviceTypeTopicExpression;
    private Pattern deviceTypeTopicPattern;
    private int timeout;

    @Override
    public List<DeviceData> convert(String topic, MqttMessage msg) throws Exception {
        String data = new String(msg.getPayload(), StandardCharsets.UTF_8);
        log.trace("Parsing json message: {}", data);

        if (filterExpression != null && !filterExpression.isEmpty()) {
            try {
                log.debug("Data before filtering {}", data);
                DocumentContext document = JsonPath.parse(data);
                document = JsonPath.parse((Object) document.read(filterExpression));
                data = document.jsonString();
                log.debug("Data after filtering {}", data);
            } catch (RuntimeException e) {
                log.debug("Failed to apply filter expression: {}", filterExpression);
                throw new RuntimeException("Failed to apply filter expression " + filterExpression);
            }
        }

        JsonNode node = mapper.readTree(data);
        List<String> srcList;
        if (node.isArray()) {
            srcList = new ArrayList<>(node.size());
            for (int i = 0; i < node.size(); i++) {
                srcList.add(mapper.writeValueAsString(node.get(i)));
            }
        } else {
            srcList = Collections.singletonList(data);
        }

        return parse(topic, srcList);
    }

    private List<DeviceData> parse(String topic, List<String> srcList) throws ParseException{
        List<DeviceData> result = new ArrayList<>(srcList.size());
        for (String src : srcList) {
            Configuration conf = Configuration.builder()
                    .options(Option.SUPPRESS_EXCEPTIONS).build();

            DocumentContext document = JsonPath.using(conf).parse(src);
            long ts = System.currentTimeMillis();
            String deviceName;
            String deviceType = null;
            if (!StringUtils.isEmpty(deviceNameTopicExpression)) {
                deviceName = evalDeviceName(topic);
            } else {
                deviceName = eval(document, deviceNameJsonExpression);
            }
            if (!StringUtils.isEmpty(deviceTypeTopicExpression)) {
                deviceType = evalDeviceType(topic);
            } else if (!StringUtils.isEmpty(deviceTypeJsonExpression)) {
                deviceType = eval(document, deviceTypeJsonExpression);
            }

            if (!StringUtils.isEmpty(deviceName)) {
                List<KvEntry> attrData = getKvEntries(document, attributes);
                List<TsKvEntry> tsData = getTsKvEntries(document, timeseries, ts);
                result.add(new DeviceData(deviceName, deviceType, attrData, tsData, timeout));
            }
        }
        return result;
    }

    private String evalDeviceName(String topic) {
        if (deviceNameTopicPattern == null) {
            deviceNameTopicPattern = Pattern.compile(deviceNameTopicExpression);
        }
        Matcher matcher = deviceNameTopicPattern.matcher(topic);
        while (matcher.find()) {
            return matcher.group();
        }
        return null;
    }

    private String evalDeviceType(String topic) {
        if (deviceTypeTopicPattern == null) {
            deviceTypeTopicPattern = Pattern.compile(deviceTypeTopicExpression);
        }
        Matcher matcher = deviceTypeTopicPattern.matcher(topic);
        while (matcher.find()) {
            return matcher.group();
        }
        return null;
    }

}
