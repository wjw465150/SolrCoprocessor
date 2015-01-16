package wjw.hbase.solr;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.List;
import java.util.NavigableMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.hadoop.hbase.Cell;
import org.apache.hadoop.hbase.CellUtil;
import org.apache.hadoop.hbase.CoprocessorEnvironment;
import org.apache.hadoop.hbase.client.Delete;
import org.apache.hadoop.hbase.client.Durability;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.coprocessor.BaseRegionObserver;
import org.apache.hadoop.hbase.coprocessor.ObserverContext;
import org.apache.hadoop.hbase.coprocessor.RegionCoprocessorEnvironment;
import org.apache.hadoop.hbase.regionserver.wal.WALEdit;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.log4j.Logger;
import org.wjw.efjson.JsonArray;
import org.wjw.efjson.JsonObject;

import com.leansoft.bigqueue.BigArrayImpl;
import com.leansoft.bigqueue.BigQueueImpl;
import com.leansoft.bigqueue.IBigQueue;

public class SolrRegionObserver extends BaseRegionObserver {
	private static Logger log = Logger.getLogger(SolrRegionObserver.class);

	static final String PREFIX_HBASE_SOLR = "hbase.solr.";
	static final String HBASE_SOLR_QUEUEDIR = PREFIX_HBASE_SOLR + "queueDir";
	static final String HBASE_SOLR_SOLRURL = PREFIX_HBASE_SOLR + "solrUrl";
	static final String HBASE_SOLR_CORENAME = PREFIX_HBASE_SOLR + "coreName";
	static final String HBASE_SOLR_CONNECTTIMEOUT = PREFIX_HBASE_SOLR + "connectTimeout";
	static final String HBASE_SOLR_READTIMEOUT = PREFIX_HBASE_SOLR + "readTimeout";

	static final String F_SEPARATOR = "#";
	static final String F_ID = "id";
	static final String F_TABLENAME = "t_s";
	static final String F_ROWKEY = "r_s";
	static final String F_UPDATETIME = "u_dt";

	static {
		JsonObject.setDateFormat(new SimpleDateFormat(SolrTools.LOGDateFormatPattern));
	}

	private String queueDir; //本地BigQueue的目录

	private String solrUrl; //Solr的添加索引的URL,多个以逗号分隔

	private String coreName; //core名字

	private int connectTimeout = 60 * 1000; //连接超时(毫秒)

	private int readTimeout = 60 * 1000; //读超时(毫秒)

	private JsonArray _stateArray;
	private java.util.List<String> _urlUpdates;

	private Lock _lockPost = new ReentrantLock();
	private int _indexPost = -1;

	private IBigQueue _bqUpdate;
	private IBigQueue _bqDelete;

	private ScheduledExecutorService _scheduleSync = Executors.newSingleThreadScheduledExecutor(); //刷新Solr集群状态的Scheduled
	private ScheduledExecutorService _scheduleSolrUpdate = Executors.newSingleThreadScheduledExecutor(); //向Solr集群Update数据的Scheduled
	private ScheduledExecutorService _scheduleSolrDelete = Executors.newSingleThreadScheduledExecutor(); //向Solr集群Delete数据的Scheduled

	private String sanitizeFilename(String unsanitized) {
		return unsanitized.replaceAll("[\\?\\\\/:|<>\\*]", " ") // filter out ? \ / : | < > *
		.replaceAll("\\s", "_"); // white space as underscores
	}

	public String getSolrUpdateUrl() {
		if (_urlUpdates.size() == 1) {
			return _urlUpdates.get(0);
		}

		_lockPost.lock();
		try {
			_indexPost++;
			if (_indexPost >= _urlUpdates.size()) {
				_indexPost = 0;
			}

			return _urlUpdates.get(_indexPost);
		} finally {
			_lockPost.unlock();
		}
	}

	private void solrUpdate(JsonObject doc) throws Exception {
		JsonObject jsonResponse = null;
		Exception ex = null;
		for (int i = 0; i < _urlUpdates.size(); i++) {
			try {
				jsonResponse = SolrTools.updateDoc(getSolrUpdateUrl(), connectTimeout, readTimeout, doc);
				if (SolrTools.getStatus(jsonResponse) == 0) {
					ex = null;
					break;
				}
			} catch (Exception e) {
				ex = e;
				try {
					Thread.sleep(100);
				} catch (InterruptedException e1) {
				}
			}
		}
		if (ex != null) {
			throw ex;
		}

		if (SolrTools.getStatus(jsonResponse) != 0) {
			throw new RuntimeException(jsonResponse.encodePrettily());
		}
	}

	private void solrDelete(JsonObject doc) throws Exception {
		JsonObject jsonResponse = null;
		Exception ex = null;
		for (int i = 0; i < _urlUpdates.size(); i++) {
			try {
				jsonResponse = SolrTools.delDoc(getSolrUpdateUrl(), connectTimeout, readTimeout, doc);
				if (SolrTools.getStatus(jsonResponse) == 0) {
					ex = null;
					break;
				}
			} catch (Exception e) {
				ex = e;
				try {
					Thread.sleep(100);
				} catch (InterruptedException e1) {
				}
			}
		}
		if (ex != null) {
			throw ex;
		}

		if (SolrTools.getStatus(jsonResponse) != 0) {
			throw new RuntimeException(jsonResponse.encodePrettily());
		}
	}

	private void solrCommit() throws Exception {
		JsonObject jsonResponse = null;
		Exception ex = null;
		for (int i = 0; i < _urlUpdates.size(); i++) {
			try {
				jsonResponse = SolrTools.solrCommit(getSolrUpdateUrl(), connectTimeout, readTimeout);
				if (SolrTools.getStatus(jsonResponse) == 0) {
					ex = null;
					break;
				}
			} catch (Exception e) {
				ex = e;
				try {
					Thread.sleep(100);
				} catch (InterruptedException e1) {
				}
			}
		}
		if (ex != null) {
			throw ex;
		}

		if (SolrTools.getStatus(jsonResponse) != 0) {
			throw new RuntimeException(jsonResponse.encodePrettily());
		}
	}

	public SolrRegionObserver() {
		super();
	}

	@Override
	public void start(CoprocessorEnvironment e) throws IOException {
		org.apache.hadoop.conf.Configuration conf = e.getConfiguration();

		queueDir = conf.get(HBASE_SOLR_QUEUEDIR);

		solrUrl = conf.get(HBASE_SOLR_SOLRURL); //必须设置
		coreName = conf.get(HBASE_SOLR_CORENAME); //必须设置

		connectTimeout = conf.getInt(HBASE_SOLR_CONNECTTIMEOUT, 60);
		readTimeout = conf.getInt(HBASE_SOLR_READTIMEOUT, 60);

		if (queueDir == null) {
			queueDir = System.getProperty("java.io.tmpdir");
		}

		if (solrUrl == null) {
			throw new java.lang.VerifyError("solrUrl Not Null!");
		}

		if (coreName == null) {
			throw new java.lang.VerifyError("coreName Not Null!");
		}

		if (connectTimeout < 0) {
			connectTimeout = 60 * 1000;
		} else {
			connectTimeout = connectTimeout * 1000;
		}

		if (readTimeout < 0) {
			readTimeout = 60 * 1000;
		} else {
			readTimeout = readTimeout * 1000;
		}

		//初始化Cloud
		_stateArray = SolrTools.getClusterState(solrUrl, coreName, connectTimeout, readTimeout);
		if (_stateArray == null) {
			throw new RuntimeException("can not connect Solr Cloud:" + "coreName:" + coreName + "URLS:" + solrUrl);
		} else {
			log.info("Solr Cloud Status:" + _stateArray.encodePrettily());
		}

		this._urlUpdates = new java.util.ArrayList<String>(_stateArray.size());
		for (int i = 0; i < _stateArray.size(); i++) {
			JsonObject jNode = _stateArray.<JsonObject> get(i);
			if (jNode.getString("state").equalsIgnoreCase("active") || jNode.getString("state").equalsIgnoreCase("recovering")) {
				this._urlUpdates.add(jNode.getString("base_url") + "/" + coreName + "/update");
			}
		}

		int syncinterval = 30;
		_scheduleSync.scheduleWithFixedDelay(new Runnable() {
			@Override
			public void run() { //刷新Solr集群状态
				JsonArray stateArray = SolrTools.getClusterState(solrUrl, coreName, connectTimeout, readTimeout);
				if (stateArray == null) {
					log.warn("can not connect Solr Cloud:" + solrUrl);
					return;
				}
				if (_stateArray.encode().equals(stateArray.encode())) {
					return;
				}
				_stateArray = stateArray;

				java.util.List<String> newUrlUpdates = new java.util.ArrayList<String>(stateArray.size());
				for (int i = 0; i < stateArray.size(); i++) {
					JsonObject jj = stateArray.<JsonObject> get(i);
					if (jj.getString("state").equalsIgnoreCase("active") || jj.getString("state").equalsIgnoreCase("recovering")) {
						newUrlUpdates.add(jj.getString("base_url") + "/" + coreName + "/update");
					}
				}

				_lockPost.lock();
				try {
					_urlUpdates.clear();
					_urlUpdates = newUrlUpdates;
				} finally {
					_lockPost.unlock();
				}
			}
		}, 10, syncinterval, TimeUnit.SECONDS);

		//初始化IBigQueue
		_bqUpdate = new BigQueueImpl(queueDir, sanitizeFilename("hbase_solr_update"), BigArrayImpl.MINIMUM_DATA_PAGE_SIZE);
		_bqUpdate.gc();
		_scheduleSolrUpdate.scheduleWithFixedDelay(new Runnable() {
			@Override
			public void run() { //向solr更新数据
				byte[] data;
				try {
					while ((data = _bqUpdate.dequeue()) != null) {
						JsonObject doc = new JsonObject(new String(data, SolrTools.UTF_8));
						try {
							solrUpdate(doc);
						} catch (Exception e) {
							_bqUpdate.enqueue(data); //发生错误后重新放回BigQueue,然后跳出循环!
							log.error(e.getMessage(), e);
							break;
						}
					}

					if ((Calendar.getInstance().get(Calendar.HOUR_OF_DAY) % 24) == 3) { //每天3点执行,删除不用的hbase_solr_update文件
						_bqUpdate.gc();
					}
				} catch (Exception e) {
					log.error(e.getMessage(), e);
				}
			}
		}, 1, 1, TimeUnit.SECONDS);

		_bqDelete = new BigQueueImpl(queueDir, sanitizeFilename("hbase_solr_delete"), BigArrayImpl.MINIMUM_DATA_PAGE_SIZE);
		_bqDelete.gc();
		_scheduleSolrDelete.scheduleWithFixedDelay(new Runnable() {
			@Override
			public void run() { //向solr删除数据
				byte[] data;
				try {
					while ((data = _bqDelete.dequeue()) != null) {
						JsonObject doc = new JsonObject(new String(data, SolrTools.UTF_8));
						try {
							solrDelete(doc);
						} catch (Exception e) {
							_bqDelete.enqueue(data); //发生错误后重新放回BigQueue,然后跳出循环!
							log.error(e.getMessage(), e);
							break;
						}
					}

					if ((Calendar.getInstance().get(Calendar.HOUR_OF_DAY) % 24) == 4) { //每天4点执行,删除不用的hbase_solr_delete文件
						_bqDelete.gc();
					}
				} catch (Exception e) {
					log.error(e.getMessage(), e);
				}
			}
		}, 1, 1, TimeUnit.SECONDS);

		log.info("STARTED: " + SolrRegionObserver.class.getName());
	}

	@Override
	public void stop(CoprocessorEnvironment e) throws IOException {
		_scheduleSync.shutdown();
		_scheduleSolrUpdate.shutdown();
		_scheduleSolrDelete.shutdown();

		if (_bqUpdate != null) {
			try {
				_bqUpdate.close();
			} catch (Exception ex) {
				log.error(ex.getMessage(), ex);
			}
		}

		if (_bqDelete != null) {
			try {
				_bqDelete.close();
			} catch (Exception ex) {
				log.error(ex.getMessage(), ex);
			}
		}

		log.info("STOPD: " + SolrRegionObserver.class.getName());
	}

	@Override
	public void postPut(ObserverContext<RegionCoprocessorEnvironment> e, Put put, WALEdit edit, Durability durability)
	    throws IOException {
		String tableName = e.getEnvironment().getRegion().getRegionInfo().getTable().getNameAsString();
		if (tableName.startsWith("hbase:")) { //元数据表,忽略!
			return;
		}
		String rowKey = Bytes.toString(put.getRow());

		JsonObject jsonPut = new JsonObject();
		jsonPut.putString(F_ID, tableName + F_SEPARATOR + rowKey);
		jsonPut.putObject(F_TABLENAME, (new JsonObject()).putString("set", tableName));
		jsonPut.putObject(F_ROWKEY, (new JsonObject()).putString("set", rowKey));
		jsonPut.putObject(F_UPDATETIME, (new JsonObject()).putString("set", SolrTools.solrDateFormat.format(new java.util.Date())));

		String cFamily = null;
		String cQualifier = null;
		String cValue = null;
		NavigableMap<byte[], List<Cell>> map = put.getFamilyCellMap();
		for (List<Cell> cells : map.values()) {
			for (Cell cell : cells) {
				cFamily = new String(CellUtil.cloneFamily(cell));
				cQualifier = new String(CellUtil.cloneQualifier(cell));
				cValue = new String(CellUtil.cloneValue(cell), SolrTools.UTF_8);
				if (cQualifier.endsWith("_hs") || cQualifier.endsWith("_s")) { //string
					jsonPut.putObject(cFamily + F_SEPARATOR + cQualifier, (new JsonObject()).putString("set", cValue));
				} else if (cQualifier.endsWith("_ht") || cQualifier.endsWith("_t")) { //text_general
					jsonPut.putObject(cFamily + F_SEPARATOR + cQualifier, (new JsonObject()).putString("set", cValue));
				} else if (cQualifier.endsWith("_hdt") || cQualifier.endsWith("_dt")) { //date
					jsonPut.putObject(cFamily + F_SEPARATOR + cQualifier, (new JsonObject()).putString("set", cValue));
				} else if (cQualifier.endsWith("_hi") || cQualifier.endsWith("_i")) { //int
					jsonPut.putObject(cFamily + F_SEPARATOR + cQualifier, (new JsonObject()).putNumber("set", Integer.valueOf(cValue)));
				} else if (cQualifier.endsWith("_hl") || cQualifier.endsWith("_l")) { //long
					jsonPut.putObject(cFamily + F_SEPARATOR + cQualifier, (new JsonObject()).putNumber("set", Long.valueOf(cValue)));
				} else if (cQualifier.endsWith("_hf") || cQualifier.endsWith("_f")) { //float
					jsonPut.putObject(cFamily + F_SEPARATOR + cQualifier, (new JsonObject()).putNumber("set", Float.valueOf(cValue)));
				} else if (cQualifier.endsWith("_hd") || cQualifier.endsWith("_d")) { //double
					jsonPut.putObject(cFamily + F_SEPARATOR + cQualifier, (new JsonObject()).putNumber("set", Double.valueOf(cValue)));
				} else if (cQualifier.endsWith("_hb") || cQualifier.endsWith("_b")) { //boolean
					jsonPut.putObject(cFamily + F_SEPARATOR + cQualifier, (new JsonObject()).putBoolean("set", Boolean.valueOf(cValue)));
				} else { //不是需要的类型,跳出!
					continue;
				}
			}
		}

		log.debug("postPut!!! " + jsonPut.encode());
		_bqUpdate.enqueue(jsonPut.encode().getBytes(SolrTools.UTF_8));
	}

	@Override
	public void postDelete(ObserverContext<RegionCoprocessorEnvironment> e, Delete delete, WALEdit edit,
	    Durability durability) throws IOException {
		String tableName = e.getEnvironment().getRegion().getRegionInfo().getTable().getNameAsString();
		if (tableName.startsWith("hbase:")) { //元数据表,忽略!
			return;
		}
		String rowKey = new String(delete.getRow());

		try {
			JsonObject jsonDel = new JsonObject();
			//jsonDel.putObject("delete", (new JsonObject()).putString("query", F_TABLENAME + ":\"" + tableName + "\" AND " + F_ROWKEY + ":\"" + rowKey + "\""));
			jsonDel.putObject("delete", (new JsonObject()).putString("query", F_ID + ":\"" + tableName + F_SEPARATOR + rowKey + "\""));

			log.debug("postDelete!!! " + jsonDel.encode());
			_bqDelete.enqueue(jsonDel.encode().getBytes(SolrTools.UTF_8));
		} catch (Exception ex) {
			log.warn(ex);
		}
	}
}
