/*
 * Copyright 2016 Taylor Caldwell
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

package net.rithms.riot.api;

import java.lang.reflect.Type;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import com.google.gson.Gson;

import net.rithms.riot.api.request.RequestMethod;
import net.rithms.riot.constant.Platform;
import net.rithms.riot.constant.Region;

abstract public class ApiMethod {

	private final ApiConfig config;
	private final String service;
	private Platform platform = null;
	@Deprecated
	private Region region = null;
	private String urlBase;
	private final List<UrlParameter> urlParameters = new LinkedList<UrlParameter>();
	private final List<HttpHeadParameter> httpHeadParameters = new LinkedList<HttpHeadParameter>();
	private RequestMethod method = RequestMethod.GET;
	private String body = null;
	private Type returnType = null;

	private boolean requireApiKey = false;

	protected ApiMethod(ApiConfig config, String service) {
		this.config = config;
		this.service = service;
	}

	protected void add(HttpHeadParameter p) {
		httpHeadParameters.add(p);
	}

	protected void add(UrlParameter p) {
		urlParameters.add(p);
	}

	protected void addApiKeyParameter() {
		add(new HttpHeadParameter("X-Riot-Token", getConfig().getKey()));
	}

	public void buildJsonBody(Map<String, Object> map) {
		body = new Gson().toJson(map);
	}

	public void checkRequirements() throws RiotApiException {
		if (doesRequireApiKey() && getConfig().getKey() == null) {
			throw new RiotApiException(RiotApiException.MISSING_API_KEY);
		}
	}

	public boolean doesRequireApiKey() {
		return requireApiKey;
	}

	public String getBody() {
		return body;
	}

	protected ApiConfig getConfig() {
		return config;
	}

	public Platform getPlatform() {
		return platform;
	}

	@Deprecated
	public Region getRegion() {
		return region;
	}

	public Type getReturnType() {
		return returnType;
	}

	public List<HttpHeadParameter> getHttpHeadParameters() {
		return httpHeadParameters;
	}

	public RequestMethod getMethod() {
		return method;
	}

	public String getService() {
		return service;
	}

	public String getUrl() {
		StringBuilder url = new StringBuilder(urlBase);
		char connector = '?';
		for (UrlParameter p : urlParameters) {
			url.append(connector).append(p.toString());
			connector = '&';
		}
		return url.toString();
	}

	protected void requireApiKey() {
		requireApiKey = true;
	}

	protected void setPlatform(Platform platform) {
		this.platform = platform;
	}

	@Deprecated
	protected void setRegion(Region region) {
		this.region = region;
	}

	protected void setReturnType(Type returnType) {
		this.returnType = returnType;
	}

	protected void setMethod(RequestMethod method) {
		this.method = method;
	}

	protected void setUrlBase(String urlBase) {
		this.urlBase = urlBase;
	}

	@Override
	public String toString() {
		return getClass().getSimpleName();
	}
}