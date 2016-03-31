# 基于solr实现hbase的二级索引

## [X] 目的:
由于hbase基于行健有序存储，在查询时使用行健十分高效，然后想要实现关系型数据库那样可以随意组合的`多条件查询`、`查询总记录数`、`分页`等就比较麻烦了。想要实现这样的功能,我们可以采用两种方法:
  
1. 使用hbase提供的filter,
2. 自己实现二级索引,通过二级索引 查询多符合条件的行健,然后再查询hbase.
  
第一种方法不多说了,使用起来很方便,但是局限性也很大,hbase的filter是直接扫记录的,如果数据范围很大,会导致查询速度很慢.
所以如果能先使 用行健把记录缩小到一个较小范围,那么就比较适合,否则就不适用了.此外该方法不能解决获取总数的为.   
  
第二种是适用范围就比较广泛了,不过根据实现二级索引的方式解决的问题也不同.这里我们选择solr主要是因为solr可以很轻松实现各种查询(本来就是全文检索引擎). 

## [X] 实现思路:
其实hbase结合solr实现方法还是比较简单的,重点在于一些实现细节上.  
  
将hbase记录写入solr的关键就在于hbase提供的`Coprocessor`, `Coprocessor`提供了两个实现:`endpoint`和`observer`,  
`endpoint`相当于关系型数据库的存储过程,而observer则相当于 触 发器.说到这相信大家应该就明白了,我们要利用的就是`observer`.  
`observer`允许我们在记录put前后做一些处理,而我们就是通过`postPut`将记录同步写入solr(关于Coprocessor具体内容请自行查资料).  
  
而写入solr这块就比较简单了,主要是要考虑性能!默认情况下hbase每写一条数据就会向出发一次`postPut`,  
如果直接提交个solr,速度会非常慢,而且如果有异常处理起来也会非常的麻烦.因此要自己实现一个本地可持久化的队列,通过后台线程异步向向solr提交. 

## [X] 实现代码:
参见[SolrCoprocessor](https://github.com/wjw465150/SolrCoprocessor)

## [X] 当前限制:
+ 不能单独删除指定的`列簇(${Family})`,必须是删除`行(Row)`,或者删除指定的`列(${Family}#${Qualifier})`!  

## [X] 部署:

### 在Solr的schema.xml文件里必须有如下动态字段:
```xml
   <dynamicField name="*_i"  type="int"    indexed="true"  stored="true"/>
   <dynamicField name="*_l"  type="long"   indexed="true"  stored="true"/>
   <dynamicField name="*_f"  type="float"  indexed="true"  stored="true"/>
   <dynamicField name="*_d"  type="double" indexed="true"  stored="true"/>
   <dynamicField name="*_b"  type="boolean" indexed="true" stored="true"/>
   <dynamicField name="*_s"  type="string"  indexed="true"  stored="true" />
   <dynamicField name="*_t"  type="text_general"    indexed="true"  stored="true"/>
   <dynamicField name="*_dt"  type="date"    indexed="true"  stored="true"/>
```
说明:
>  
solr里的每一条Dcoument对应HBase表里的一条记录  
每一条Dcoument里缺省都会有4个字段:  
`id`格式是:`${TableName}#${RowKey}`  
`t_s`格式是:`${TableName}`  
`r_s`格式是:`${RowKey}`  
`u_dt`格式是:`${d当前更新时的日期和时间}`  
其他字段格式是:`${Family}#${Qualifier}`  
如果HBase表里的字段需要在solr里索引,那么`Qualifier`设计为已`_(i|l|f|d|b|s|t|dt)`结尾的solr动态字段!  

### 停止HBase:
在master hbase server上执行:
```bash
${HBASE_HOME}/bin/stop-hbase.sh
```

### 修改所有`Region Servers`的`$(HBASE_HOME}/conf/hbase-site.xml`配置文件 
在最后添加:
```xml
  <!-- 调试时,将hbase的hbase.coprocessor.abortonerror设置成true,待确定Coprocessor运行正常后在改为false.
  此步骤非必要,但是如果Coprocessor有问题会导致所有Region Server无法启动!
  -->
  <property>
    <name>hbase.coprocessor.abortonerror</name>
    <value>true</value>
  </property>
  <!-- Solr Coprocessor -->
  <property>
    <name>hbase.coprocessor.region.classes</name>
    <value>wjw.hbase.solr.SolrRegionObserver</value>
  </property>

  <!-- 本地保存Queue的目录名,没有时使用:System.getProperty("java.io.tmpdir")得来的值  -->
  <property>
    <name>hbase.solr.queueDir</name>
    <value>/tmp</value>
  </property>  
  <!-- Solr的URL,多个以逗号分隔 -->
  <property>
    <name>hbase.solr.solrUrl</name>
    <value>http://${solrHost1}:8983/solr/,http://${solrHost2}:8983/solr/</value>
  </property>  
  <!-- core名字  -->
  <property>
    <name>hbase.solr.coreName</name>
    <value>hbase</value>
  </property>  
  <!-- 连接超时(秒) -->
  <property>
    <name>hbase.solr.connectTimeout</name>
    <value>60</value>
  </property>  
  <!-- 读超时(秒) -->
  <property>
    <name>hbase.solr.readTimeout</name>
    <value>60</value>
  </property>  
```

### 复制`SolrCoprocessor-X.X.X.jar`文件
把`SolrCoprocessor-X.X.X.jar`复制到所有的`Region Servers`的`$(HBASE_HOME}/lib/`目录下

### 启动HBase:
在master hbase server上执行:
```bash
${HBASE_HOME}/bin/start-hbase.sh
```

### 测试:
```bash
/opt/hbase/bin/hbase shell
>status
>create 'demotable','col'
>describe  'demotable'
>list 'demotable'
>put 'demotable','myrow-1','col:q1','value-1'
>put 'demotable','myrow-1','col:q2_s','value-2-测试'
>put 'demotable','myrow-1','col:name_t','张三 李四 王五'
>put 'demotable','myrow-1','col:q3_s','value-3-测试'
>scan 'demotable'
```
