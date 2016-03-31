package wjw.hbase.solr;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.wjw.efjson.JsonArray;
import org.wjw.efjson.JsonObject;

abstract class SolrTools {
	static final String UTF_8 = "UTF-8"; //HTTP请求字符集
	static final String LOGDateFormatPattern = "yyyy-MM-dd'T'HH:mm:ss'Z'";
	static final SafeSimpleDateFormat solrDateFormat = new SafeSimpleDateFormat(LOGDateFormatPattern);

	static final int PAGE_SIZE = 100;
	static final String SOLR_SERVER_URLS = "solrServerUrls";
	static final String CONNECT_TIMEOUT = "connectTimeout";
	static final String READ_TIMEOUT = "readTimeout";

	static final String F_ID = "id";
	static final String F_VERSION = "_version_";

	static {
		System.setProperty("http.keepAlive", "true");
		System.setProperty("http.maxConnections", "200");
		System.setProperty("sun.net.http.errorstream.enableBuffering", "true");
		System.setProperty("sun.net.http.retryPost", "false");  //解决HttpURLConnection当发生SocketTimeoutException异常时的自己重试BUG!
	}
	
	private SolrTools() {
		//
	}

	public static String sanitizeFilename(String unsanitized) {
		return unsanitized.replaceAll("[\\?\\\\/:|<>\\*]", " ") // filter out ? \ / : | < > *
		    .replaceAll("\\s", "_"); // white space as underscores
	}

	@SuppressWarnings("unchecked")
	//返回的格式是:[
	//  {"state":"active","base_url":"http://192.168.0.143:8983/solr","core":"collection1","node_name":"192.168.0.143:8983_solr","leader":"true"}
	//  ,{"state":"active","base_url":"http://192.168.0.147:8983/solr","core":"collection1","node_name":"192.168.0.147:8983_solr"}
	//  ,{"state":"active","base_url":"http://192.168.0.118:8983/solr","core":"collection1","node_name":"192.168.0.118:8983_solr"}
	//  ,{"state":"active","base_url":"http://192.168.0.145:8983/solr","core":"collection1","node_name":"192.168.0.145:8983_solr","leader":"true"}
	//  ,{"state":"active","base_url":"http://192.168.0.150:8983/solr","core":"collection1","node_name":"192.168.0.150:8983_solr"}
	//  ,{"state":"active","base_url":"http://192.168.0.190:8983/solr","core":"collection1","node_name":"192.168.0.190:8983_solr"}
	//  ,{"state":"active","base_url":"http://192.168.0.146:8983/solr","core":"collection1","node_name":"192.168.0.146:8983_solr","leader":"true"}
	//  ,{"state":"active","base_url":"http://192.168.0.200:8983/solr","core":"collection1","node_name":"192.168.0.200:8983_solr"}
	//  ,{"state":"active","base_url":"http://192.168.0.58:8983/solr","core":"collection1","node_name":"192.168.0.58:8983_solr"}
	//  ,{"state":"active","base_url":"http://192.168.0.144:8983/solr","core":"collection1","node_name":"192.168.0.144:8983_solr","leader":"true"}
	//  ,{"state":"active","base_url":"http://192.168.0.108:8983/solr","core":"collection1","node_name":"192.168.0.108:8983_solr"}
	//  ,{"state":"active","base_url":"http://192.168.0.191:8983/solr","core":"collection1","node_name":"192.168.0.191:8983_solr"}
	//  ]
	//错误返回null
	public static JsonArray getClusterState(String solrServers, String coreName, int connectTimeout, int readTimeout) {
		List<String> urlArray = Arrays.asList(solrServers.split(","));
		Collections.shuffle(urlArray);

		String clusterstate;
		JsonArray result = null;
		for (int i = 0; i < urlArray.size(); i++) {
			try {
	      int solrVersion = getSolrVersion(urlArray.get(i), connectTimeout, readTimeout);
        if (solrVersion > 4) {
          if (urlArray.get(i).endsWith("/")) {
            clusterstate = urlArray.get(i) + "admin/zookeeper?wt=json&detail=true&path=%2Fclusterstate.json&view=graph&_=" + System.currentTimeMillis();
          } else {
            clusterstate = urlArray.get(i) + "/admin/zookeeper?wt=json&detail=true&path=%2Fclusterstate.json&view=graph&_=" + System.currentTimeMillis();
          }
        } else {
          if (urlArray.get(i).endsWith("/")) {
            clusterstate = urlArray.get(i) + "zookeeper?wt=json&detail=true&path=%2Fclusterstate.json&_=" + System.currentTimeMillis();
          } else {
            clusterstate = urlArray.get(i) + "/zookeeper?wt=json&detail=true&path=%2Fclusterstate.json&_=" + System.currentTimeMillis();
          }
        }

				String bodyText = doGetProcess(clusterstate, connectTimeout, readTimeout, null, null);

				JsonObject jsonBody = new JsonObject(bodyText);
				String data = jsonBody.getObject("znode").getString("data");
				JsonObject jsonData = new JsonObject(data);

				JsonObject shards = jsonData.getObject(coreName).getObject("shards");
				result = new JsonArray();
				for(String fName : shards.getFieldNames()) {
					JsonObject jsonShared = shards.getObject(fName);
					JsonObject replicas = jsonShared.getObject("replicas");

					Map<String, Object> nodes = replicas.toMap();
					for (Object node : nodes.values()) {
						JsonObject jsonNode = new JsonObject((Map<String, Object>) node);
						result.addObject(jsonNode);
					}
				}

				break;
			} catch (Exception e) {
				ConnectException ee = new ConnectException("URL:[" + solrServers + "],错误消息:" + e.getMessage());
				ee.initCause(e);
				ee.printStackTrace();
				result = null;
			}
		}

		return result;
	}

  private static int getSolrVersion(String solrURL, int connectTimeout, int readTimeout) {
    try {
      String clusterUrl;
      if (solrURL.endsWith("/")) {
        clusterUrl = solrURL + "admin/info/system?wt=json&_=" + System.currentTimeMillis();
      } else {
        clusterUrl = solrURL + "/admin/info/system?wt=json&_=" + System.currentTimeMillis();
      }
      String bodyText = doGetProcess(clusterUrl, connectTimeout, readTimeout, null, null);

      JsonObject jsonBody = new JsonObject(bodyText);
      String data = jsonBody.getObject("lucene").getString("solr-spec-version");

      return Integer.parseInt(data.substring(0, 1));
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }
	
	/**
	 * 从Solr返回的对象中,返回状态码
	 * 
	 * @param solrResponse
	 *          - Solr返回的状态码
	 */
	public static int getStatus(JsonObject solrResponse) {
		return solrResponse.getObject("responseHeader").getInteger("status").intValue();
	}

  public static JsonObject updateDoc(String urlUpdate, int connectTimeout, int readTimeout, JsonObject doc)
      throws IOException {
    JsonObject solrResponse = new JsonObject(doPostProcess(urlUpdate, connectTimeout, readTimeout, "[" + doc.encode() + "]", null, null));
    return solrResponse;
  }
	
	public static JsonObject updateDocs(String urlUpdate, int connectTimeout, int readTimeout, JsonArray docs)
	    throws IOException {
		JsonObject solrResponse = new JsonObject(doPostProcess(urlUpdate, connectTimeout, readTimeout, docs.encode(), null,
		    null));
		return solrResponse;
	}

	public static JsonObject delDoc(String urlUpdate, int connectTimeout, int readTimeout, JsonObject doc)
	    throws IOException {
		JsonObject solrResponse = new JsonObject(doPostProcess(urlUpdate, connectTimeout, readTimeout, doc.encode(), null,
		    null));
		return solrResponse;
	}

	public static JsonObject getDoc(String urlGet, int connectTimeout, int readTimeout, String id) throws IOException {
		JsonObject solrResponse = new JsonObject(doGetProcess(urlGet + URLEncoder.encode(id, UTF_8), connectTimeout,
		    readTimeout, null, null));
		return solrResponse.getObject("doc");
	}

	public static JsonObject selectDocs(String urlSelect, int connectTimeout, int readTimeout, String query, int start,
	    int pageSize, String cursorMark) throws IOException {
		String httpUrl;
		if (cursorMark == null) {
			httpUrl = urlSelect + "?sort=id+asc&wt=json&q=" + URLEncoder.encode(query, UTF_8) + "&start=" + start + "&rows="
			    + pageSize;
		} else if (cursorMark.equals("*")) {
			httpUrl = urlSelect + "?cursorMark=*" + "&sort=id+asc&wt=json&q=" + URLEncoder.encode(query, UTF_8) + "&rows="
			    + pageSize;
		} else {
			httpUrl = urlSelect + "?cursorMark=" + URLEncoder.encode(cursorMark, UTF_8) + "&sort=id+asc&wt=json&q="
			    + URLEncoder.encode(query, UTF_8) + "&rows=" + pageSize;
		}
		JsonObject solrResponse = new JsonObject(doGetProcess(httpUrl, connectTimeout, readTimeout, null, null));

		return solrResponse;
	}

	public static JsonObject solrCommit(String urlUpdate, int connectTimeout, int readTimeout) throws IOException {
		JsonObject doc = new JsonObject("{\"commit\":{\"softCommit\": true}}");
		JsonObject solrResponse = new JsonObject(doPostProcess(urlUpdate, connectTimeout, readTimeout, doc.encode(), null,
		    null));
		return solrResponse;
	}

	/**
	 * 处理HTTP的GET请求,如果不需要BASIC验证,把user以及pass设置为null值
	 * 
	 * @param urlstr
	 *          请求的URL
	 * @param user
	 *          用户名
	 * @param pass
	 *          口令
	 * @return 服务器的返回信息
	 * @throws IOException
	 */
	private static String doGetProcess(String urlstr, int connectTimeout, int readTimeout, String user, String pass)
	    throws IOException {
		URL url = new URL(urlstr);

		HttpURLConnection conn = null;
		BufferedReader reader = null;
		try {
			conn = (HttpURLConnection) url.openConnection();
			conn.setConnectTimeout(connectTimeout);
			conn.setReadTimeout(readTimeout);
			conn.setUseCaches(false);
			conn.setDoOutput(false);
			conn.setDoInput(true);
			conn.setRequestProperty("Accept", "*/*");
			if (user != null && pass != null) {
				conn.setRequestProperty("Authorization",
				    "Basic " + new String(Base64.encodeBytes((user + ":" + pass).getBytes(UTF_8)))); //需要BASIC验证
			}

			conn.connect();

			if (conn.getResponseCode() == HttpURLConnection.HTTP_OK) {
				reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), UTF_8));
			} else {
				reader = new BufferedReader(new InputStreamReader(conn.getErrorStream(), UTF_8));
			}
			String line;
			StringBuilder result = new StringBuilder();

			int i = 0;
			while ((line = reader.readLine()) != null) {
				i++;
				if (i != 1) {
					result.append("\n");
				}
				result.append(line);
			}
			return result.toString();
		} finally {
			if (reader != null) {
				try {
					reader.close();
				} catch (IOException ex) {
				}
			}

			if (conn != null) {
				try {
					conn.disconnect();
				} catch (Exception ex) {
				}
			}
		}
	}

	/**
	 * 处理HTTP的POST请求,如果不需要BASIC验证,把user以及pass设置为null值
	 * 
	 * @param urlstr
	 *          请求的URL
	 * @param data
	 *          入队列的消息内容
	 * @param user
	 *          用户名
	 * @param pass
	 *          口令
	 * @return 服务器的返回信息
	 * @throws IOException
	 */
	private static String doPostProcess(String urlstr, int connectTimeout, int readTimeout, String data, String user,
	    String pass) throws IOException {
		URL url = new URL(urlstr);

		HttpURLConnection conn = null;
		BufferedReader reader = null;
		OutputStreamWriter writer = null;
		try {
			conn = (HttpURLConnection) url.openConnection();
			conn.setConnectTimeout(connectTimeout);
			conn.setReadTimeout(readTimeout);
			conn.setUseCaches(false);
			conn.setDoOutput(true);
			conn.setDoInput(true);
			conn.setRequestProperty("Accept", "*/*");
			conn.setRequestProperty("Content-Type", "application/json;charset=" + UTF_8);
			if (user != null && pass != null) {
				conn.setRequestProperty("Authorization",
				    "Basic " + new String(Base64.encodeBytes((user + ":" + pass).getBytes(UTF_8)))); //需要BASIC验证
			}

			conn.connect();

			writer = new OutputStreamWriter(conn.getOutputStream(), UTF_8);
			writer.write(data);
			writer.flush();

			if (conn.getResponseCode() == HttpURLConnection.HTTP_OK) {
				reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), UTF_8));
			} else {
				reader = new BufferedReader(new InputStreamReader(conn.getErrorStream(), UTF_8));
			}
			String line;
			StringBuilder result = new StringBuilder();

			int i = 0;
			while ((line = reader.readLine()) != null) {
				i++;
				if (i != 1) {
					result.append("\n");
				}
				result.append(line);
			}
			return result.toString();
		} finally {
			if (reader != null) {
				try {
					reader.close();
				} catch (IOException ex) {
				}
			}

			if (writer != null) {
				try {
					writer.close();
				} catch (IOException ex) {
				}
			}

			if (conn != null) {
				try {
					conn.disconnect();
				} catch (Exception ex) {
				}
			}
		}

	}

	public static class WrapperEntry<K, V> {
		final public java.util.Date _birthday = new java.util.Date();
		public K _key;
		public V _value;

		public WrapperEntry(K key, V value) {
			_key = key;
			_value = value;
		}

	}
}
