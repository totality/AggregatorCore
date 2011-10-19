package com.k_int.repository.handlers

@GrabResolver(name='es', root='https://oss.sonatype.org/content/repositories/releases')

@Grab(group='com.gmongo', module='gmongo', version='0.5.1')
@Grab(group='org.elasticsearch', module='elasticsearch-lang-groovy', version='0.17.8')

import com.gmongo.GMongo
import org.apache.commons.logging.LogFactory
import grails.converters.*

class XCRIHandler {

  private static final log = LogFactory.getLog(this)

  // This handler processes XCRI documents... After the handler is invoked, the local mongodb
  // will contain a new database called xcri and a collection called courses. If rest = true is configured in
  // /etc/mongodb.conf you can use a URL like http://localhost:28017/xcri/courses/ to enumerate all courses
  // The handler also inserts records into an elasticsearch cluster, using a courses collection, you can 
  // query this with URL's like 
  // http://localhost:9200/_all/course/_search?q=painting%20AND%20A2
  // Which will search all indexes (In ES, an index is like a solr core) for all items of type course with the given parameters.
  // This handler creates an index(core) with name courses, so to search this specific core:
  // http://localhost:9200/courses/course/_search?q=painting%20AND%20A2
  // Or search everything
  // http://localhost:9200/_search?q=painting%20AND%20A2

  // handlers have access to the repository mongo service.. suggest you use http://blog.paulopoiati.com/2010/06/20/gmongo-0-5-released/
  def getHandlerName() {
    "XCRI_CAP"
  }

  def getRevision() {
    1
  }

  def getPreconditions() {
    [
      'p.rootElementNamespace=="http://xcri.org/profiles/catalog"'
    ]
  }

  def process(props, ctx) {
    log.debug("process....");

    def start_time = System.currentTimeMillis();
    def course_count = 0;

    // Get hold of some services we might use ;)
    def mongo = new com.gmongo.GMongo();
    def db = mongo.getDB("xcri")
    Object eswrapper = ctx.getBean('ESWrapperService');
    org.elasticsearch.groovy.node.GNode esnode = eswrapper.getNode()
    org.elasticsearch.groovy.client.GClient esclient = esnode.getClient()

    log.debug("After call to getbean-eswrapper : ${eswrapper}");

    // Start processing proper

    props.response.eventLog.add([type:"msg",msg:"This is a message from the downloaded XCRI handler"])

    def d2 = props.xml.declareNamespace(['xcri':'http://xcri.org/profiles/catalog', 
                                         'xsi':'http://www.w3.org/2001/XMLSchema-instance',
                                         'xhtml':'http://www.w3.org/1999/xhtml'])

    // def xcri = new groovy.xml.Namespace("http://xcri.org/profiles/catalog", 'xcri') 

    log.debug("root element namespace: ${d2.namespaceURI()}");
    log.debug("lookup namespace: ${d2.lookupNamespace('xcri')}");
    log.debug("d2.name : ${d2.name()}");

    // Properties contain an xml element, which is the parsed document
    def id1 = d2.'xcri:provider'.'xcri:identifier'.text()

    props.response.eventLog.add([type:"msg",msg:"Identifier for this XCRI document: ${id1}"])

    d2.'xcri:provider'.'xcri:course'.each { crs ->

      def crs_identifier = crs.'xcri:identifier'.text();
      def crs_internal_uri = "uri:${props.owner}:xcri:${id1}:${crs_identifier}";

      log.debug("Processing course: ${crs_identifier}");
      props.response.eventLog.add([type:"msg",msg:"Validating course entry ${crs_internal_uri} - ${crs.'xcri:title'}"]);

      log.debug("looking up course with identifier ${crs_internal_uri}");
      def course_as_pojo = db.courses.findOne(identifier: crs_internal_uri.toString())

      if ( course_as_pojo != null ) {
        log.debug("Located existing record... updating");
      }
      else {
        log.debug("No existing course information for ${crs_internal_uri}, create new record");
        course_as_pojo = [:]
      }

      course_as_pojo.identifier = crs_internal_uri.toString();
      course_as_pojo.title = crs.'xcri:title'?.text()?.toString();
      course_as_pojo.descriptions = [:]
      crs.'xcri:description'.each { desc ->
        String desc_type = desc.@'xsi:type'?.text()?.toString()
        if ( ( desc_type != null ) && ( desc_type.length() > 0 ) ) {
          course_as_pojo.descriptions[desc_type] = desc?.text()?.toString();
        }
      }
      course_as_pojo.url = crs.'xcri:url'?.text()?.toString()
      course_as_pojo.subject = []
      crs.'subject'.each { subj ->
        course_as_pojo.subject.add( subj.text()?.toString() );
      }

      // def course_as_json = course_as_pojo as JSON;
      // log.debug("The course as JSON is ${course_as_json.toString()}");
      course_count++

      log.debug("Saving mongo instance of course....${crs_internal_uri}");

      // db.courses.update([identifier:crs_internal_uri.toString()],course_as_pojo, true);
      db.courses.save(course_as_pojo);

      log.debug("Saved pojo: ${course_as_pojo} identifier will be \"${course_as_pojo['_id'].toString()}\"");
      // Mongo inserts an _id into the record.. we can reuse that

      log.debug("Sending record to es");
      def future = esclient.index {
        index "courses"
        type "course"
        id course_as_pojo['_id'].toString()
        source course_as_pojo
      }
      log.debug("Indexed $future.response.index/$future.response.type/$future.response.id")
    }

    def elapsed = System.currentTimeMillis() - start_time
    props.response.eventLog.add([type:"msg",msg:"Completed processing of ${course_count} courses from catalog ${id1} for provider ${props.owner} in ${elapsed}ms"]);
  }

  def setup(ctx) {
    log.debug("This is the XCRI handler setup method");
  }
}
