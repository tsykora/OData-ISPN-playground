package org.tsykora.odata.producer;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

import org.codehaus.jackson.map.ObjectMapper;
import org.core4j.Func;
import org.core4j.Func1;
import org.infinispan.Cache;
import org.infinispan.manager.DefaultCacheManager;
import org.infinispan.query.CacheQuery;
import org.infinispan.query.SearchManager;
import org.odata4j.core.OEntity;
import org.odata4j.core.OEntityId;
import org.odata4j.core.OEntityKey;
import org.odata4j.core.OExtension;
import org.odata4j.core.OFunctionParameter;
import org.odata4j.core.OSimpleObject;
import org.odata4j.edm.EdmComplexType;
import org.odata4j.edm.EdmDataServices;
import org.odata4j.edm.EdmDecorator;
import org.odata4j.edm.EdmEntityContainer;
import org.odata4j.edm.EdmEntitySet;
import org.odata4j.edm.EdmEntityType;
import org.odata4j.edm.EdmFunctionImport;
import org.odata4j.edm.EdmFunctionParameter;
import org.odata4j.edm.EdmGenerator;
import org.odata4j.edm.EdmSchema;
import org.odata4j.edm.EdmSimpleType;
import org.odata4j.edm.EdmType;
import org.odata4j.exceptions.NotImplementedException;
import org.odata4j.producer.BaseResponse;
import org.odata4j.producer.CountResponse;
import org.odata4j.producer.EntitiesResponse;
import org.odata4j.producer.EntityIdResponse;
import org.odata4j.producer.EntityQueryInfo;
import org.odata4j.producer.EntityResponse;
import org.odata4j.producer.ODataContext;
import org.odata4j.producer.ODataProducer;
import org.odata4j.producer.QueryInfo;
import org.odata4j.producer.Responses;
import org.odata4j.producer.edm.MetadataProducer;
import org.odata4j.producer.inmemory.BeanBasedPropertyModel;
import org.odata4j.producer.inmemory.EnumsAsStringsPropertyModelDelegate;
import org.odata4j.producer.inmemory.InMemoryComplexTypeInfo;
import org.odata4j.producer.inmemory.InMemoryTypeMapping;
import org.odata4j.producer.inmemory.PropertyModel;

/**
 * ODataProducer with implemented direct access to Infinispan Cache.
 */
public class InfinispanProducer implements ODataProducer {

    private static final boolean DUMP = false;
    private final Logger log = Logger.getLogger(InfinispanProducer.class.getName());

    private static void dump(Object msg) {
        if (DUMP) {
            System.out.println(msg);
        }
    }

    public static final String ID_PROPNAME = "EntityId";
    private final String namespace;
    private final String containerName;
    private final int maxResults;

    // preserve the order of registration
    private final Map<String, InMemoryEntityInfo<?>> eis = new LinkedHashMap<String, InMemoryEntityInfo<?>>();

    private final Map<String, InMemoryComplexTypeInfo<?>> complexTypes = new LinkedHashMap<String, InMemoryComplexTypeInfo<?>>();
    private EdmDataServices metadata;
    private final EdmDecorator decorator;
    private final MetadataProducer metadataProducer;
    private final InMemoryTypeMapping typeMapping;
    private final boolean flattenEdm;
    private static final int DEFAULT_MAX_RESULTS = 100;

    // TODO: properly decide: not static??? - cache instance is running with producer instance
    private DefaultCacheManager defaultCacheManager;
    // for faster cache access
    private HashMap<String, Cache> caches = new HashMap<String, Cache>();


    /**
     * Creates a new instance of an in-memory POJO producer.
     *
     * @param namespace the namespace of the schema registrations
     */
    public InfinispanProducer(String namespace, String ispnConfigFile) {
        this(namespace, DEFAULT_MAX_RESULTS, ispnConfigFile);
    }

    /**
     * Creates a new instance of an in-memory POJO producer.
     *
     * @param namespace  the namespace of the schema registrations
     * @param maxResults the maximum number of entities to return in a single call
     */
    public InfinispanProducer(String namespace, int maxResults, String ispnConfigFile) {
        this(namespace, null, maxResults, null, null, ispnConfigFile);
    }

    /**
     * Creates a new instance of an in-memory POJO producer.
     *
     * @param namespace     the namespace of the schema registrations
     * @param containerName the container name for generated metadata
     * @param maxResults    the maximum number of entities to return in a single call
     * @param decorator     a decorator to use for edm customizations
     * @param typeMapping   optional mapping between java types and edm types, null for default
     */
    public InfinispanProducer(String namespace, String containerName, int maxResults, EdmDecorator decorator, InMemoryTypeMapping typeMapping,
                              String ispnConfigFile) {
        this(namespace, containerName, maxResults, decorator, typeMapping,
                true, ispnConfigFile); // legacy: flatten edm
    }

    /**
     * Do everything important here while creating new producer instance.
     */
    public <TEntity, TKey> InfinispanProducer(String namespace, String containerName, int maxResults,
                                              EdmDecorator decorator, InMemoryTypeMapping typeMapping,
                                              boolean flattenEdm, String ispnConfigFile) {
        this.namespace = namespace;
        this.containerName = containerName != null && !containerName.isEmpty() ? containerName : "Container";
        this.maxResults = maxResults;
        this.decorator = decorator;
        this.metadataProducer = new MetadataProducer(this, decorator);
        this.typeMapping = typeMapping == null ? InMemoryTypeMapping.DEFAULT : typeMapping;
        this.flattenEdm = flattenEdm;


        // TODO add possibility for passing configurations (global, local)
        try {
            // true = start it + start defined caches
            defaultCacheManager = new DefaultCacheManager(ispnConfigFile, true);


            Set<String> cacheNames = defaultCacheManager.getCacheNames();

            for (String cacheName : cacheNames) {
//            dump("Starting cache with name " + cacheName + " on defaultCacheManager...");
//            defaultCacheManager.startCache(cacheName);

                dump("Registering cache with name " + cacheName + " in Producer...");
                // cacheName = entitySetName
                eis.put(cacheName, null);
            }

        } catch (IOException e) {
            System.out.println(" PROBLEMS WITH CREATING DEFAULT CACHE MANAGER! ");
            e.printStackTrace();
        }
    }

    /**
     * Look into global map for registered cache. Avoiding multiple asking CacheManager.
     * <p/>
     * If there is no cache with given name, get it from CacheManager and store.
     *
     * @param cacheName
     * @return
     */
    private Cache getCache(String cacheName) {
        if (caches.get(cacheName) != null) {

            return this.caches.get(cacheName);

        } else {

            try {
                System.out.println("Starting cache with name " + cacheName + " on defaultCacheManager...");
                defaultCacheManager.startCache(cacheName);
                System.out.println("Cache started!....");
                Cache cache = defaultCacheManager.getCache(cacheName);

                cache.put("simpleKey1", "simpleValue1"); // starts cache
                dump("Cache " + cacheName + " status: " + cache.getStatus());

                System.out.println(" simpleKey1\", \"simpleValue1 ------ PUTTED INTO CACHE, now some json stuff:");

                this.caches.put(cacheName, cache);
                return cache;
            } catch (Exception e) {
                System.out.println(" \n\n ***** ERROR DURING STARTING CACHE __ DURING EXPERIMENTS WITH INDEXING ***** \n\n");
                e.printStackTrace();
            }
        }
        return this.caches.get(cacheName);
    }

    @Override
    public EdmDataServices getMetadata() {
        if (metadata == null) {
            metadata = newEdmGenerator(namespace, typeMapping, ID_PROPNAME, eis, complexTypes).generateEdm(decorator).build();
        }
        return metadata;
    }

    protected InMemoryEdmGenerator newEdmGenerator(String namespace, InMemoryTypeMapping typeMapping, String idPropName, Map<String, InMemoryEntityInfo<?>> eis,
                                                   Map<String, InMemoryComplexTypeInfo<?>> complexTypesInfo) {
        return new InMemoryEdmGenerator(namespace, containerName, typeMapping, ID_PROPNAME, eis, complexTypesInfo, this.flattenEdm);
    }

    @Override
    public MetadataProducer getMetadataProducer() {
        return metadataProducer;
    }

    @Override
    public void close() {
    }


    // Not supported -- use defined OData functions
    @Override
    public EntitiesResponse getEntities(ODataContext context, String entitySetName, final QueryInfo queryInfo) {
        throw new NotImplementedException();
    }


    // Not supported -- use defined OData functions
    @Override
    public CountResponse getEntitiesCount(ODataContext context, String entitySetName, final QueryInfo queryInfo) {
        throw new NotImplementedException();
    }

    // Not supported -- use defined OData functions
    @Override
    public EntityResponse getEntity(ODataContext context, final String entitySetName, final OEntityKey entityKey, final EntityQueryInfo queryInfo) {
        throw new NotImplementedException();
    }

    // Not supported -- use defined OData functions
    @Override
    public void mergeEntity(ODataContext context, String entitySetName, OEntity entity) {
        throw new NotImplementedException();
    }

    // Not supported -- use defined OData functions
    @Override
    public void updateEntity(ODataContext context, String entitySetName, OEntity entity) {
        throw new NotImplementedException();
    }


    // Not supported -- use defined OData functions
    @Override
    public void deleteEntity(ODataContext context, String entitySetName, OEntityKey entityKey) {
        throw new NotImplementedException();
    }

    // Not supported -- use defined OData functions
    @Override
    public EntityResponse createEntity(ODataContext context, String entitySetName, final OEntity entity) {
        throw new NotImplementedException();
    }

    // Not supported -- use defined OData functions
    @Override
    public EntityResponse createEntity(ODataContext context, String entitySetName, OEntityKey entityKey, String navProp, OEntity entity) {
        throw new NotImplementedException();
    }

    // Not supported (How to navigate entities inside NOSQL, schema-less store?)
    @Override
    public BaseResponse getNavProperty(ODataContext context, String entitySetName, OEntityKey entityKey, String navProp, QueryInfo queryInfo) {
        throw new NotImplementedException();
    }

    // Not supported (How to navigate entities inside NOSQL, schema-less store?)
    @Override
    public CountResponse getNavPropertyCount(ODataContext context, String entitySetName, OEntityKey entityKey, String navProp, QueryInfo queryInfo) {
        throw new NotImplementedException();
    }

    // Not supported (How to navigate entities inside NOSQL, schema-less store? Any links here? Check OData and confirm.)
    @Override
    public EntityIdResponse getLinks(ODataContext context, OEntityId sourceEntity, String targetNavProp) {
        throw new NotImplementedException();
    }

    // Not supported (How to navigate entities inside NOSQL, schema-less store? Any links here? Check OData and confirm.)
    @Override
    public void createLink(ODataContext context, OEntityId sourceEntity, String targetNavProp, OEntityId targetEntity) {
        throw new NotImplementedException();
    }

    // Not supported (How to navigate entities inside NOSQL, schema-less store? Any links here? Check OData and confirm.)
    @Override
    public void updateLink(ODataContext context, OEntityId sourceEntity, String targetNavProp, OEntityKey oldTargetEntityKey, OEntityId newTargetEntity) {
        throw new NotImplementedException();
    }

    // Not supported (How to navigate entities inside NOSQL, schema-less store? Any links here? Check OData and confirm.)
    @Override
    public void deleteLink(ODataContext context, OEntityId sourceEntity, String targetNavProp, OEntityKey targetEntityKey) {
        throw new NotImplementedException();
    }


    /**
     * HTTP POST request accepted, issued on service/cacheName_put?params...&$filter=... URI
     *
     * @return
     */
    private BaseResponse callFunctionPut(String setNameWhichIsCacheName, String entryKey, CachedValue cachedValue) {

        System.out.println("Putting into " + setNameWhichIsCacheName + " cache, entryKey: " + entryKey + " value: " + cachedValue.toString());
        getCache(setNameWhichIsCacheName).put(entryKey, cachedValue);

        // TODO: avoid this when FLAG don't return when put = true
//        if (flag) {
//            TODO: prepare BaseResponse? and FunctionResource.java for it!
//            return null; // clients will get NO_CONTENT response (It is successful kind of response!)
//        }
        return callFunctionGet(setNameWhichIsCacheName, entryKey, null);
    }


    public BaseResponse callFunctionGet(String setNameWhichIsCacheName, String entryKey,
                                        QueryInfo queryInfo) {

        List<Object> queryResult = null;

        if (entryKey != null) {
            // ignore query and return value directly
            CachedValue value = (CachedValue) getCache(setNameWhichIsCacheName).get(entryKey);
            if (value != null) {
                // TODO: look closely at formatting this
                return Responses.simple(EdmSimpleType.STRING, "jsonValue", value.getJsonValueWrapper().getJson());
            } else {
                // client will get NO_CONTENT (204)
                return null;
            }

        } else {
            // I need some filter
            // TODO: don't process filters when cache is not Queryable (Log warning + serve only direct gets) !!! perf+

            // Maybe remove this check?
            if (queryInfo.filter == null) {
                return Responses.error(new OErrorImpl("Parameter 'key' is not specified, therefore we want to get entries using query filter." +
                        " \n However, $filter is not specified as well."));
            }

            System.out.println("Query report for $filter " + queryInfo.filter.toString());

            SearchManager searchManager = org.infinispan.query.Search.getSearchManager(getCache(setNameWhichIsCacheName));
            MapQueryExpressionVisitor mapQueryExpressionVisitor =
                    new MapQueryExpressionVisitor(searchManager.buildQueryBuilderForClass(CachedValue.class).get());
            mapQueryExpressionVisitor.visit(queryInfo.filter);

            // Query cache here adn get results based on constructed Lucene query
            CacheQuery queryFromVisitor = searchManager.getQuery(mapQueryExpressionVisitor.getBuiltLuceneQuery(),
                    CachedValue.class);
            // pass query result to the function final response
            queryResult = queryFromVisitor.list();

            System.out.println(" \n\n SEARCH RESULTS GOT BY VISITOR!!! APPROACH: size:" + queryResult.size() + ":");

            for (Object one_result : queryResult) {
                System.out.println(one_result);
            }

            // *********************************************************************************
            // We have set queryResult object containing list of results from querying the cache
            // Now apply other filters/order by/top/skip etc. requests

            if (queryInfo.top != null) {
                // client wants to only a specified count of first "top" results
                // use it for collections of objects??
            }

            if (queryInfo.skip != null) {
                // client wants to skip particular number of results -- skip them and return only what's requested
                // use it for collections of objects??

            }

            if (queryInfo.orderBy != null) {
                // TODO: I hope this can be definitely added into LUCENE QUERY builder and we can get back
                // already ordered objects
            }
        }

        if (queryResult.size() > 0) {
            StringBuilder sb = new StringBuilder();
            // build response
            for (Object one_result : queryResult) {
                CachedValue cv = (CachedValue) one_result;
                sb.append(cv.getJsonValueWrapper().getJson());
                sb.append("\n");
            }
            // stack more json strings responses
            return Responses.simple(EdmSimpleType.STRING, "jsonValue", sb.toString());
        } else {
            // no results found, clients will get NO_CONTENT response
            return null;
        }
    }

    public BaseResponse callFunctionRemove(String setNameWhichIsCacheName, String entryKey) {
        // TODO: later, avoid returning by using flag (or add option to call uri)
        CachedValue removed = (CachedValue) getCache(setNameWhichIsCacheName).remove(entryKey);
        return Responses.simple(EdmSimpleType.STRING, "jsonValue", removed.getJsonValueWrapper().getJson());
    }

    public BaseResponse callFunctionReplace(String setNameWhichIsCacheName, String entryKey, CachedValue cachedValue) {

        System.out.println("Replacing in " + setNameWhichIsCacheName + " cache, entryKey: " + entryKey + " value: " + cachedValue.toString());
        getCache(setNameWhichIsCacheName).replace(entryKey, cachedValue);

        // TODO: avoid this when FLAG don't return when put = true
//        if (flag) {
//            TODO: prepare BaseResponse? and FunctionResource.java for it!
//            return null; // clients will get NO_CONTENT response (It is successful kind of response!)
//        }
        return callFunctionGet(setNameWhichIsCacheName, entryKey, null);
    }

    /**
     * The heart of our producer.
     * We can go the way of having cacheName_get/put/delete/update and call particular aforementioned methods
     * (i.e. create, delete, update, get entity)
     * <p/>
     * TODO: implement real - cache related functions like: Start, Stop etc.
     * <p/>
     * <p/>
     * Use it like: http://localhost:8887/ODataInfinispanEndpoint.svc/mySpecialNamedCache_get?key=%27jsonKey1%27"
     * Use HTTP POST for create / PUT entity into the cache
     *
     * @param context
     * @param function
     * @param params
     * @param queryInfo
     * @return
     */
    @Override
    public BaseResponse callFunction(ODataContext context, EdmFunctionImport function, Map<String, OFunctionParameter> params,
                                     QueryInfo queryInfo) {


        // every function call need to have key or queryInfo specified
        // TODO: modify condition to not only .filter (but also other constraints?) queryInfo != null is not enough
        // TODO: it's not null for every case I think


        if (params.get("key") != null || queryInfo.filter != null) {

            // TODO: what is mutual for all, at least 2 function should be here and pass as a parameter to child function call

            String setNameWhichIsCacheName = function.getEntitySet().getName();
            CachedValue cachedValue = null;

            String entryKey = null;
            if (params.get("key") != null) {
                entryKey = params.get("key").getValue().toString();
            }

            // Extract client payload in case of POST and PUT
            boolean extractClientPayload = (function.getHttpMethod().equals("POST") && function.getName().endsWith("_put")) ||
                    ((function.getHttpMethod().equals("PUT") && function.getName().endsWith("_replace")));

            if (extractClientPayload) {
                // TODO: Q: why is that JSON encoded into ByteArrayStream on server side? Can ve avoid this?
                OSimpleObject payloadOSimpleObject = (OSimpleObject) params.get("payload").getValue();
                try {

                    InputStream jsonInputStream = (InputStream) payloadOSimpleObject.getValue();

//                    ByteArrayInputStream inputStream = (ByteArrayInputStream) payloadOSimpleObject.getValue();
//                    BufferedReader rd = new BufferedReader(new InputStreamReader(inputStream));


//                    BufferedReader rd = new BufferedReader(new InputStreamReader((InputStream) payloadOSimpleObject.getValue()));
//                    String result = "";
//                    String line;
//                    while ((line = rd.readLine()) != null) {
//                        result += line;
//                    }
//                    rd.close();
//
//
//                    dump("Extracted JSON from payload: " + result);

                    // TODO: store only jsonValue field into the cache for indexing...
                    // TODO: EXTRACT it from { "d" : { "jsonValue" : "ENTRY_FOR_INDEX" } } format

                    Object extractedJsonValue = "";

//                    result:
//                    {"d" : {"jsonValue" : "{
//                    "entityClass":"org.infinispan.odata.Person",
//                    "id":"person1",
//                    "gender":"MALE",
//                    "firstName":"John",
//                    "lastName":"Smith",
//                    "age":24}"
//                    }}

                    // TODO: to method - extractJsonValue()
                    // (InputStream) payloadOSimpleObject.getValue())

                    ObjectMapper mapper = new ObjectMapper();
//                    System.out.println("CallFunction: Reading by Jackson mapper, json to read: " + result);
//                    Map<String, Object> entryAsMap = (Map<String, Object>) mapper.readValue(result, Object.class);
                    System.out.println("CallFunction: reading json from inputStream");
                    Map<String, Object> entryAsMap = (Map<String, Object>) mapper.readValue(jsonInputStream, Object.class);

                    System.out.println("CallFunction: Reading JSON response from put.");
                    // field "d"
                    for (String field : entryAsMap.keySet()) {
                        System.out.println("CallFunction: * Field: " + field + " *");
                        System.out.println("CallFunction: Class: " + entryAsMap.get(field).getClass());
                        System.out.println("CallFunction: value: " + entryAsMap.get(field));

                        Map<String, Object> childEntry = (Map<String, Object>) entryAsMap.get(field);

                        System.out.println("writer1: " + mapper.writeValueAsString(childEntry));

                        for (String fieldInner : childEntry.keySet()) {
                            System.out.println("Field inner: " + fieldInner);
                            System.out.println(childEntry.get(fieldInner));
                            System.out.println("writer2: " +mapper.writeValueAsString(fieldInner));
                        }

                        extractedJsonValue = childEntry.get("jsonValue");
                        System.out.println("extractedJsonValue: " + extractedJsonValue);
                        System.out.println("writer3: " + mapper.writeValueAsString(extractedJsonValue));
                    }

                    cachedValue = new CachedValue(mapper.writeValueAsString(extractedJsonValue));
                } catch (Exception e) {
                    return Responses.error(new OErrorImpl("Problems with extracting jsonValue from payload. " + e.getMessage()));
                }
            }


            if (function.getHttpMethod().equals("POST") && function.getName().endsWith("_put")) {
                return callFunctionPut(setNameWhichIsCacheName, entryKey, cachedValue);
            }

            if (function.getHttpMethod().equals("GET") && function.getName().endsWith("_get")) {
                return callFunctionGet(setNameWhichIsCacheName, entryKey, queryInfo);
            }

            if (function.getHttpMethod().equals("DELETE") && function.getName().endsWith("_remove")) {
                return callFunctionRemove(setNameWhichIsCacheName, entryKey);
            }

            if (function.getHttpMethod().equals("PUT") && function.getName().endsWith("_replace")) {
                callFunctionReplace(setNameWhichIsCacheName, entryKey, cachedValue);
            }

            return Responses.error(new OErrorImpl(
                    " HTTP GET method AND cache method ending _get,\n" +
                            " HTTP POST method AND cache method ending _put,\n" +
                            " HTTP DELETE method AND cache method ending _remove\n" +
                            " OR HTTP PUT method AND cache method ending _replace was expected.\n" +
                            " Function name was: " + function.getName() + " HTTP method was: " + function.getHttpMethod()));

        }

        return Responses.error(new OErrorImpl("Parameter 'key' or $filter needs to be specified."));
    }

    @Override
    public <TExtension extends OExtension<ODataProducer>> TExtension findExtension(Class<TExtension> clazz) {
        return null;
    }


    /**
     * TODO: Do we really need this? Can we find another (more simple) way of registering entities and drop this?
     *
     * @param <TEntity>
     */
    public class InMemoryEntityInfo<TEntity> {

        // we are maintaining collection of these entities - they are mapped to EntitySetName in [eis] hash map
        String entitySetName;
        String entityTypeName;
        String[] keys;
        Class<TEntity> entityClass;
        Func<Iterable<TEntity>> get; //returning defined apply()

        Func1<Object, HashMap<String, Object>> id;
        PropertyModel properties;
        boolean hasStream;

        public String getEntitySetName() {
            return entitySetName;
        }

        public String getEntityTypeName() {
            return entityTypeName;
        }

        public String[] getKeys() {
            return keys;
        }

        public Class<TEntity> getEntityClass() {
            return entityClass;
        }

        public Func<Iterable<TEntity>> getGet() {
            return get;
        }

        public Func1<Object, HashMap<String, Object>> getId() {
            return id;
        }

        public PropertyModel getPropertyModel() {
            return properties;
        }

        public boolean getHasStream() {
            return hasStream;
        }

        public Class<?> getSuperClass() {
            return entityClass.getSuperclass() != null && !entityClass.getSuperclass().equals(Object.class) ? entityClass.getSuperclass() : null;
        }
    }


    /**
     * TODO: Can we simplify this even more?
     * <p/>
     * There is a workaround in method toEdmProperties(). Key and Value entity properties are directly considered as
     * byte[].class.
     */
    public class InMemoryEdmGenerator implements EdmGenerator {

        private static final boolean DUMP = false;
        //      private static void dump(String msg) { if (DUMP) System.out.println(msg); }
        private final Logger log = Logger.getLogger(InMemoryEdmGenerator.class.getName());
        private final String namespace;
        private final String containerName;
        protected final InMemoryTypeMapping typeMapping;
        protected final Map<String, InMemoryEntityInfo<?>> eis; // key: EntitySet name
        protected final Map<String, InMemoryComplexTypeInfo<?>> complexTypeInfo; // key complex type edm type name
        protected final List<EdmComplexType.Builder> edmComplexTypes = new ArrayList<EdmComplexType.Builder>();
        // Note, assumes each Java type will only have a single Entity Set defined for it.
        protected final Map<Class<?>, String> entitySetNameByClass = new HashMap<Class<?>, String>();
        // build these as we go now.
        protected Map<String, EdmEntityType.Builder> entityTypesByName = new HashMap<String, EdmEntityType.Builder>();
        protected Map<String, EdmEntitySet.Builder> entitySetsByName = new HashMap<String, EdmEntitySet.Builder>();
        protected final boolean flatten;

        public InMemoryEdmGenerator(String namespace, String containerName, InMemoryTypeMapping typeMapping,
                                    String idPropertyName, Map<String, InfinispanProducer.InMemoryEntityInfo<?>> eis,
                                    Map<String, InMemoryComplexTypeInfo<?>> complexTypes) {
            this(namespace, containerName, typeMapping, idPropertyName, eis, complexTypes, true);
        }

        public InMemoryEdmGenerator(String namespace, String containerName, InMemoryTypeMapping typeMapping,
                                    String idPropertyName, Map<String, InfinispanProducer.InMemoryEntityInfo<?>> eis,
                                    Map<String, InMemoryComplexTypeInfo<?>> complexTypes, boolean flatten) {
            this.namespace = namespace;
            this.containerName = containerName;
            this.typeMapping = typeMapping;
            this.eis = eis;
            this.complexTypeInfo = complexTypes;

            // if not registering, this is null
//         for (Map.Entry<String, InfinispanProducer.InMemoryEntityInfo<?>> e : eis.entrySet()) {
//            // e.getValue().entityClass = MyInternalCacheEntry , e.getKey() = "CacheEntries"
//            // e.getValue() = InMemoryEntityInfo
//            entitySetNameByClass.put(e.getValue().entityClass, e.getKey());
//         }
            this.flatten = flatten;
        }

        @Override
        public EdmDataServices.Builder generateEdm(EdmDecorator decorator) {

            List<EdmSchema.Builder> schemas = new ArrayList<EdmSchema.Builder>();
            List<EdmEntityContainer.Builder> containers = new ArrayList<EdmEntityContainer.Builder>();

//            createComplexTypes(decorator, edmComplexTypes);

            // creates id other basic SUPPORTED_TYPE properties(structural) entities
            createStructuralEntities(decorator);

            // TODO handle back references too
            // create hashmaps from sets

//            createNavigationProperties(associations, associationSets,
//                    entityTypesByName, entitySetsByName, entitySetNameByClass);

            EdmEntityContainer.Builder container = EdmEntityContainer.newBuilder().
                    setName(containerName).setIsDefault(true).
                    addEntitySets(entitySetsByName.values());

            // I need EntityType for EntitySet for EdmxFormatWriter

//         EdmSchema.Builder schema = EdmSchema.newBuilder().setNamespace(namespace).
//               addEntityTypes(entityTypesByName.values()).addAssociations(associations).
//               addEntityContainers(containers).addComplexTypes(edmComplexTypes);

            // fictional entity type to satisfy EdmxFormatWriter & EdmxFormatParser
//         EdmEntityType.Builder d = EdmEntityType.newBuilder().setBaseType("java.lang.String");
//            EdmEntityType.Builder eet = EdmEntityType.newBuilder().setNamespace(namespace).
//                    setName("java.lang.String").setHasStream(false);
            // java.lang.IllegalArgumentException: Root types must have keys
//         at org.odata4j.edm.EdmEntityType.<init>(EdmEntityType.java:54)


            // I don't have entity types, nor associations... however I need to register containers
//         EdmSchema.Builder schema = EdmSchema.newBuilder().setNamespace(namespace).addComplexTypes(edmComplexTypes).addEntityTypes(eet);
            EdmSchema.Builder schema = EdmSchema.newBuilder().setNamespace(namespace).addComplexTypes(edmComplexTypes);

            addFunctions(container);

            // FIXED *****************************************
            // FIXED add container after function registration / functions are added directly into container object
            containers.add(container);

            if (decorator != null) {
                schema.setDocumentation(decorator.getDocumentationForSchema(namespace));
                schema.setAnnotations(decorator.getAnnotationsForSchema(namespace));
            }

            // FIXED ********************************
            // FIXED add containers into schema so I can get it in Metadata and properly find function imports in EdmDataServices
            schema.addEntityContainers(containers);
            schemas.add(schema);
            EdmDataServices.Builder rt = EdmDataServices.newBuilder().addSchemas(schemas);
            if (decorator != null) {
                rt.addNamespaces(decorator.getNamespaces());
            }
            return rt;
        }


        // TODO: use decorator in other way if needed
        private void createStructuralEntities(EdmDecorator decorator) {
            // eis contains all of the registered entity sets.
            for (String entitySetName : eis.keySet()) {
//                EdmEntitySet.Builder ees = EdmEntitySet.newBuilder().setName(entitySetName).setEntityType(eet);
                EdmEntitySet.Builder ees = EdmEntitySet.newBuilder().setName(entitySetName);
                entitySetsByName.put(ees.getName(), ees);
            }
        }

        protected InfinispanProducer.InMemoryEntityInfo<?> findEntityInfoForClass(Class<?> clazz) {
            for (InfinispanProducer.InMemoryEntityInfo<?> typeInfo : this.eis.values()) {
                if (typeInfo.entityClass.equals(clazz)) {
                    return typeInfo;
                }
            }
            return null;
        }

        /*
        * contains all generated InMemoryEntityInfos that get created as we walk
        * up the inheritance hierarchy and find Java types that are not registered.
        */
        private Map<Class<?>, InfinispanProducer.InMemoryEntityInfo<?>> unregisteredEntityInfo =
                new HashMap<Class<?>, InfinispanProducer.InMemoryEntityInfo<?>>();

        protected InfinispanProducer.InMemoryEntityInfo<?> getUnregisteredEntityInfo(Class<?> clazz, InfinispanProducer.InMemoryEntityInfo<?> subclass) {
            InfinispanProducer.InMemoryEntityInfo<?> ei = unregisteredEntityInfo.get(clazz);
            if (ei == null) {
                ei = new InfinispanProducer.InMemoryEntityInfo();
                ei.entityTypeName = clazz.getSimpleName();
                ei.keys = subclass.keys;
                ei.entityClass = (Class) clazz;
                ei.properties = new EnumsAsStringsPropertyModelDelegate(
                        new BeanBasedPropertyModel(ei.entityClass, this.flatten));
            }
            return ei;
        }

        protected EdmEntityType.Builder createStructuralType(EdmDecorator decorator, InfinispanProducer.InMemoryEntityInfo<?> entityInfo) {

            Class<?> superClass = flatten ? null : entityInfo.getSuperClass();


            EdmEntityType.Builder eet = EdmEntityType.newBuilder().setNamespace(namespace).
                    setName(entityInfo.entityTypeName).setHasStream(entityInfo.hasStream);

            if (superClass == null) {
                eet.addKeys(entityInfo.keys);
            }

            if (decorator != null) {
                eet.setDocumentation(decorator.getDocumentationForEntityType(namespace, entityInfo.entityTypeName));
                eet.setAnnotations(decorator.getAnnotationsForEntityType(namespace, entityInfo.entityTypeName));
            }
            entityTypesByName.put(eet.getName(), eet);

            EdmEntityType.Builder superType = null;
            if (!this.flatten && entityInfo.entityClass.getSuperclass() != null && !entityInfo.entityClass.getSuperclass().equals(Object.class)) {
                InfinispanProducer.InMemoryEntityInfo<?> entityInfoSuper = findEntityInfoForClass(entityInfo.entityClass.getSuperclass());
                // may have created it along another branch in the hierarchy
                if (entityInfoSuper == null) {
                    // synthesize...
                    entityInfoSuper = getUnregisteredEntityInfo(entityInfo.entityClass.getSuperclass(), entityInfo);
                }

                superType = entityTypesByName.get(entityInfoSuper.entityTypeName);
                if (superType == null) {
                    superType = createStructuralType(decorator, entityInfoSuper);
                }
            }

            eet.setBaseType(superType);
            return eet;
        }


        /**
         * Function definitions it defines and add functions into EDM Schema these functions are callable as GET HTTP
         * operations
         * <p/>
         * <p/>
         * TODO: Define cache operations: stop, start etc. (we need to support this) We will support operations with caches.
         * <p/>
         * provides an override point for applications to add application specific EdmFunctions to their producer.
         * <p/>
         * note: if function getReturnType returns null it returns nothing in ConsumerFunctionCallRequest
         *
         * @param container the EdmEntityContainer.Builder
         */
        protected void addFunctions(EdmEntityContainer.Builder container) {

            List<EdmFunctionImport.Builder> funcImports = new LinkedList<EdmFunctionImport.Builder>();

            int i = 0;
            while (i < container.getEntitySets().size()) {
                // define functions for each entity set (each cache)

                String entitySetNameCacheName = container.getEntitySets().get(i).getName();
                List<EdmFunctionParameter.Builder> funcParameters = new LinkedList<EdmFunctionParameter.Builder>();

                EdmFunctionParameter.Builder pbKey = new EdmFunctionParameter.Builder();

                // for POST, GET, DELETE and PUT method
                pbKey.setName("key").setType(EdmType.getSimple("String")).setNullable(true).build();
                funcParameters.add(pbKey);


                // only cache name function for method POST requests
                EdmFunctionImport.Builder fb5 = new EdmFunctionImport.Builder();
                EdmFunctionImport.Builder fb6 = new EdmFunctionImport.Builder();
                EdmFunctionImport.Builder fb7 = new EdmFunctionImport.Builder();
                EdmFunctionImport.Builder fb8 = new EdmFunctionImport.Builder();

                // OData spec. HINT
//                IsBindable - 'true' indicates that the first parameter is the binding parameter
//                IsSideEffecting - 'true' defines an action rather than a function
//                IsAlwaysBindable - 'false' defines that the binding can be conditioned to the entity state.

                // IMPORTANT TASK perf+
                // Parent TODO: implement also async variants + maybe do it with advanced cache
                // TODO: and expect some flags during calls in special parameters /cache_put?key='key1'&flags='IGNORE_RETURN_VALUE,ASYNC'

                // TODO: do it like iteration through enum GET POST DELETE PUT and change Http method inside!!
                // TODO: not 4 imports, duplicate code

                // for HTTP POST (gather and emulates POST request for createEntity)
                fb5.setName(entitySetNameCacheName + "_put")
                        .setEntitySet(container.getEntitySets().get(i))
                        .setEntitySetName(entitySetNameCacheName)
                                // let return type to null to be able to directly access response
                        .setReturnType(EdmSimpleType.STRING)
                                // by specifying http method, we make from this "function" a SERVICE OPERATION kind of a "function"
                        .setHttpMethod("POST")
                        .setBindable(false)
                        .setSideEffecting(false)  // true for Action (POST)
                        .setAlwaysBindable(false)
                        .addParameters(funcParameters).build();

                // TODO: maybe change return type to something like JSON VALUE
                // TODO: so we can avoid some ByteArrayInputStream -> string,json transformations (possible?)
                fb6.setName(entitySetNameCacheName + "_get")
                        .setEntitySet(container.getEntitySets().get(i))
                        .setEntitySetName(entitySetNameCacheName)
                                // let return type to null to be able to directly access response
                        .setReturnType(EdmSimpleType.STRING)
                        .setHttpMethod("GET")
                        .setBindable(false)
                        .setSideEffecting(false)  // true for Action (POST)
                        .setAlwaysBindable(false)
                        .addParameters(funcParameters).build();

                fb7.setName(entitySetNameCacheName + "_remove")
                        .setEntitySet(container.getEntitySets().get(i))
                        .setEntitySetName(entitySetNameCacheName)
                                // let return type to null to be able to directly access response
                        .setReturnType(EdmSimpleType.STRING)
                        .setHttpMethod("DELETE")
                        .setBindable(false)
                        .setSideEffecting(false)  // true for Action (POST)
                        .setAlwaysBindable(false)
                        .addParameters(funcParameters).build();

                fb8.setName(entitySetNameCacheName + "_replace")
                        .setEntitySet(container.getEntitySets().get(i))
                        .setEntitySetName(entitySetNameCacheName)
                                // let return type to null to be able to directly access response
                        .setReturnType(EdmSimpleType.STRING)
                        .setHttpMethod("PUT")
                        .setBindable(false)
                        .setSideEffecting(false)  // true for Action (POST)
                        .setAlwaysBindable(false)
                        .addParameters(funcParameters).build();

                funcImports.add(fb5);
                funcImports.add(fb6);
                funcImports.add(fb7);
                funcImports.add(fb8);

                i++;
            }

            dump("Functions import ok...");
            container.addFunctionImports(funcImports);
        }
    }
}





