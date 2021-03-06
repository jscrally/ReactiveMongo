package reactivemongo.api.commands

import reactivemongo.api.{ ReadConcern, SerializationPack }
import reactivemongo.core.protocol.MongoWireVersion

/**
 * Implements the [[http://docs.mongodb.org/manual/applications/aggregation/ Aggregation Framework]].
 */
trait AggregationFramework[P <: SerializationPack]
    extends ImplicitCommandHelpers[P] {

  /**
   * @param batchSize the initial batch size for the cursor
   */
  case class Cursor(batchSize: Int)

  /**
   * @param pipeline the sequence of MongoDB aggregation operations
   * @param explain specifies to return the information on the processing of the pipeline
   * @param allowDiskUse enables writing to temporary files
   * @param cursor the cursor object for aggregation
   * @param bypassDocumentValidation available only if you specify the \$out aggregation operator
   * @param readConcern the read concern (since MongoDB 3.2)
   */
  case class Aggregate(
    pipeline: Seq[PipelineOperator],
    explain: Boolean = false,
    allowDiskUse: Boolean,
    cursor: Option[Cursor],
    wireVersion: MongoWireVersion,
    bypassDocumentValidation: Boolean,
    readConcern: Option[ReadConcern]
  ) extends CollectionCommand with CommandWithPack[pack.type]
      with CommandWithResult[AggregationResult]

  /**
   * @param firstBatch the documents of the first batch
   * @param cursor the cursor from the result, if any
   * @see [[Cursor]]
   */
  case class AggregationResult(
      firstBatch: List[pack.Document],
      cursor: Option[ResultCursor] = None
  ) {

    @deprecated(message = "Use [[firstBatch]]", since = "0.11.10")
    def documents = firstBatch

    @deprecated(message = "Use [[head]]", since = "0.11.10")
    def result[T](implicit reader: pack.Reader[T]): List[T] = head[T]

    /**
     * Returns the first batch as a list, using the given `reader`.
     */
    def head[T](implicit reader: pack.Reader[T]): List[T] =
      firstBatch.map(pack.deserialize(_, reader))

  }

  /** Returns a document from a sequence of element producers. */
  protected def makeDocument(elements: Seq[pack.ElementProducer]): pack.Document
  // TODO: Move to SerializationPack?

  /**
   * Returns a producer of element for the given `name` and `value`.
   *
   * @param name the element name
   * @param value the element value
   */
  protected def elementProducer(name: String, value: pack.Value): pack.ElementProducer // TODO: Move to SerializationPack?

  /** Returns an boolean as a serialized value. */
  protected def booleanValue(b: Boolean): pack.Value

  /** Returns an integer as a serialized value. */
  protected def intValue(i: Int): pack.Value

  /** Returns an long as a serialized value. */
  protected def longValue(l: Long): pack.Value

  /** Returns an double as a serialized value. */
  protected def doubleValue(d: Double): pack.Value

  /** Returns an string as a serialized value. */
  protected def stringValue(s: String): pack.Value

  /**
   * One of MongoDBs pipeline operators for aggregation.
   * Sealed as these are defined in the MongoDB specifications,
   * and clients should not have custom operators.
   */
  sealed trait PipelineOperator {
    def makePipe: pack.Value
  }

  /**
   * Reshapes a document stream by renaming, adding, or removing fields.
   * Also uses [[http://docs.mongodb.org/manual/reference/aggregation/project/#_S_project Project]] to create computed values or sub-objects.
   *
   * @param specifications The fields to include. The resulting objects will contain only these fields.
   */
  case class Project(specifications: pack.Document) extends PipelineOperator {
    val makePipe: pack.Document =
      makeDocument(Seq(elementProducer("$project", specifications)))
  }

  /**
   * Filters out documents from the stream that do not match the predicate.
   * http://docs.mongodb.org/manual/reference/aggregation/match/#_S_match
   * @param predicate the query that documents must satisfy to be in the stream
   */
  case class Match(predicate: pack.Document) extends PipelineOperator {
    val makePipe: pack.Document =
      makeDocument(Seq(elementProducer("$match", predicate)))
  }

  /**
   * Restricts the contents of the documents based on information stored in the documents themselves.
   * http://docs.mongodb.org/manual/reference/operator/aggregation/redact/#pipe._S_redact Redact
   * @param expression the redact expression
   */
  case class Redact(expression: pack.Document) extends PipelineOperator {
    val makePipe: pack.Document =
      makeDocument(Seq(elementProducer("$redact", expression)))
  }

  /**
   * Limits the number of documents that pass through the stream.
   * http://docs.mongodb.org/manual/reference/aggregation/limit/#_S_limit
   * @param limit the number of documents to allow through
   */
  case class Limit(limit: Int) extends PipelineOperator {
    val makePipe: pack.Document =
      makeDocument(Seq(elementProducer("$limit", intValue(limit))))

  }

  /**
   * _Since MongoDB 3.2:_ Performs a left outer join to an unsharded collection in the same database to filter in documents from the "joined" collection for processing.
   * https://docs.mongodb.com/v3.2/reference/operator/aggregation/lookup/#pipe._S_lookup
   *
   * @param from the collection to perform the join with
   * @param localField the field from the documents input
   * @param foreignField the field from the documents in the `from` collection
   * @param as the name of the new array field to add to the input documents
   */
  case class Lookup(
      from: String,
      localField: String,
      foreignField: String,
      as: String
  ) extends PipelineOperator {
    val makePipe: pack.Document = makeDocument(Seq(elementProducer(
      "$lookup",
      makeDocument(Seq(
        elementProducer("from", stringValue(from)),
        elementProducer("localField", stringValue(localField)),
        elementProducer("foreignField", stringValue(foreignField)),
        elementProducer("as", stringValue(as))
      ))
    )))
  }

  /**
   * The [[https://docs.mongodb.com/master/reference/operator/aggregation/filter/ \$filter]] aggregation stage.
   *
   * @param input the expression that resolves to an array
   * @param as The variable name for the element in the input array. The as expression accesses each element in the input array by this variable.
   * @param cond the expression that determines whether to include the element in the resulting array
   */
  case class Filter(input: pack.Value, as: String, cond: pack.Document)
      extends PipelineOperator {

    val makePipe: pack.Document = makeDocument(Seq(elementProducer(
      "$filter", makeDocument(Seq(
        elementProducer("input", input),
        elementProducer("as", stringValue(as)),
        elementProducer("cond", cond)
      ))
    )))
  }

  /** Filter companion */
  object Filter {
    implicit val writer: pack.Writer[Filter] =
      pack.writer[Filter] { _.makePipe }
  }

  /**
   * Skips over a number of documents before passing all further documents along the stream.
   * http://docs.mongodb.org/manual/reference/aggregation/skip/#_S_skip
   * @param skip the number of documents to skip
   */
  case class Skip(skip: Int) extends PipelineOperator {
    val makePipe: pack.Document =
      makeDocument(Seq(elementProducer("$skip", intValue(skip))))
  }

  /**
   * Randomly selects the specified number of documents from its input.
   * https://docs.mongodb.org/master/reference/operator/aggregation/sample/
   * @param size the number of documents to return
   */
  case class Sample(size: Int) extends PipelineOperator {
    val makePipe: pack.Document =
      makeDocument(Seq(elementProducer(
        "$sample",
        makeDocument(Seq(elementProducer("size", intValue(size))))
      )))
  }

  /**
   * Turns a document with an array into multiple documents,
   * one document for each element in the array.
   * http://docs.mongodb.org/manual/reference/aggregation/unwind/#_S_unwind
   * @param field the name of the array to unwind
   */
  case class Unwind(field: String) extends PipelineOperator {
    val makePipe: pack.Document =
      makeDocument(Seq(elementProducer("$unwind", stringValue("$" + field))))
  }

  /**
   * Represents one of the group operators for the "Group" Operation.
   * This class is sealed as these are defined in the MongoDB spec,
   * and clients should not need to customise these.
   */
  sealed trait GroupFunction {
    def makeFunction: pack.Value
  }

  /** Factory to declare custom call to a group function. */
  object GroupFunction {
    /**
     * Creates a call to specified group function with given argument.
     *
     * @param name The name of the group function (e.g. `\$sum`)
     * @param arg The group function argument
     * @return A group function call defined as `{ name: arg }`
     */
    def apply(name: String, arg: pack.Value): GroupFunction =
      new GroupFunction {
        val makeFunction = makeDocument(Seq(elementProducer(name, arg)))
      }
  }

  /**
   * Groups documents together to calulate aggregates on document collections.
   * This command aggregates on arbitrary identifiers.
   * Document fields identifier must be prefixed with `$`.
   * http://docs.mongodb.org/manual/reference/aggregation/group/#_S_group
   * @param identifiers any BSON value acceptable by mongodb as identifier
   * @param ops the sequence of operators specifying aggregate calculation
   */
  case class Group(identifiers: pack.Value)(ops: (String, GroupFunction)*)
      extends PipelineOperator {
    val makePipe: pack.Document =
      makeDocument(Seq(elementProducer("$group", makeDocument(Seq(
        elementProducer("_id", identifiers)
      ) ++
        ops.map({
          case (field, op) => elementProducer(field, op.makeFunction)
        })))))

  }

  /**
   * Groups documents together to calulate aggregates on document collections.
   * This command aggregates on one field.
   * http://docs.mongodb.org/manual/reference/aggregation/group/#_S_group
   * @param idField the name of the field to aggregate on
   * @param ops the sequence of operators specifying aggregate calculation
   */
  case class GroupField(idField: String)(ops: (String, GroupFunction)*)
      extends PipelineOperator {

    val makePipe = Group(stringValue("$" + idField))(ops: _*).makePipe
  }

  /**
   * Groups documents together to calulate aggregates on document collections.
   * This command aggregates on multiple fields, and they must be named.
   * http://docs.mongodb.org/manual/reference/aggregation/group/#_S_group
   * @param idFields The fields to aggregate on, and the names they should be aggregated under.
   * @param ops the sequence of operators specifying aggregate calculation
   */
  case class GroupMulti(idFields: (String, String)*)(
      ops: (String, GroupFunction)*
  ) extends PipelineOperator {
    val makePipe = Group(makeDocument(idFields.map {
      case (alias, attribute) =>
        elementProducer(alias, stringValue("$" + attribute))
    }))(ops: _*).makePipe
  }

  /**
   * [[https://docs.mongodb.org/v3.0/reference/operator/aggregation/meta/#exp._S_meta Keyword of metadata]].
   */
  sealed trait MetadataKeyword {
    /** Keyword name */
    def name: String
  }

  /**
   * Since MongoDB 3.2
   * https://docs.mongodb.com/manual/reference/operator/aggregation/indexStats/
   */
  case object IndexStats extends PipelineOperator {
    val makePipe = makeDocument(Seq(
      elementProducer("$indexStats", makeDocument(Nil))
    ))
  }

  /**
   * @param ops the number of operations that used the index
   * @param since the time from which MongoDB gathered the statistics
   */
  case class IndexStatAccesses(ops: Long, since: Long)

  /**
   * @param name the index name
   * @param key the key specification
   * @param host the hostname and port of the mongod
   * @param accesses the index statistics
   */
  case class IndexStatsResult(
    name: String,
    key: pack.Document,
    host: String,
    accesses: IndexStatAccesses
  )

  /** References the score associated with the corresponding [[https://docs.mongodb.org/v3.0/reference/operator/query/text/#op._S_text `\$text`]] query for each matching document. */
  case object TextScore extends MetadataKeyword {
    val name = "textScore"
  }

  /**
   * Represents that a field should be sorted on, as well as whether it
   * should be ascending or descending.
   */
  sealed trait SortOrder {
    /** The name of the field to be used to sort. */
    def field: String
  }

  /** Ascending sort order */
  case class Ascending(field: String) extends SortOrder

  /** Descending sort order */
  case class Descending(field: String) extends SortOrder

  /**
   * [[https://docs.mongodb.org/v3.0/reference/operator/aggregation/sort/#sort-pipeline-metadata Metadata sort]] order.
   *
   * @param keyword the metadata keyword to sort by
   */
  case class MetadataSort(
    field: String, keyword: MetadataKeyword
  ) extends SortOrder

  /**
   * Sorts the stream based on the given fields.
   * http://docs.mongodb.org/manual/reference/aggregation/sort/#_S_sort
   * @param fields the fields to sort by
   */
  case class Sort(fields: SortOrder*) extends PipelineOperator {
    val makePipe = makeDocument(Seq(
      elementProducer("$sort", makeDocument(fields.map {
        case Ascending(field)  => elementProducer(field, intValue(1))
        case Descending(field) => elementProducer(field, intValue(-1))
        case MetadataSort(field, keyword) => {
          val meta = makeDocument(Seq(
            elementProducer("$meta", stringValue(keyword.name))
          ))

          elementProducer(field, meta)
        }
      }))
    ))
  }

  /**
   * Outputs documents in order of nearest to farthest from a specified point.
   *
   * http://docs.mongodb.org/manual/reference/operator/aggregation/geoNear/#pipe._S_geoNear
   * @param near the point for which to find the closest documents
   * @param spherical if using a 2dsphere index
   * @param limit the maximum number of documents to return
   * @param maxDistance the maximum distance from the center point that the documents can be
   * @param query limits the results to the matching documents
   * @param distanceMultiplier the factor to multiply all distances returned by the query
   * @param uniqueDocs if this value is true, the query returns a matching document once
   * @param distanceField the output field that contains the calculated distance
   * @param includeLocs this specifies the output field that identifies the location used to calculate the distance
   */
  case class GeoNear(near: pack.Value, spherical: Boolean = false, limit: Long = 100, minDistance: Option[Long] = None, maxDistance: Option[Long] = None, query: Option[pack.Document] = None, distanceMultiplier: Option[Double] = None, uniqueDocs: Boolean = false, distanceField: Option[String] = None, includeLocs: Option[String] = None) extends PipelineOperator {
    def makePipe = makeDocument(
      Seq(elementProducer("$geoNear", makeDocument(Seq(
        elementProducer("near", near),
        elementProducer("spherical", booleanValue(spherical)),
        elementProducer("limit", longValue(limit))
      ) ++ Seq(
          minDistance.map(l => elementProducer("minDistance", longValue(l))),
          maxDistance.map(l => elementProducer("maxDistance", longValue(l))),
          query.map(s => elementProducer("query", s)),
          distanceMultiplier.map(d => elementProducer(
            "distanceMultiplier", doubleValue(d)
          )),
          Some(elementProducer("uniqueDocs", booleanValue(uniqueDocs))),
          distanceField.map(s =>
            elementProducer("distanceField", stringValue(s))),
          includeLocs.map(s =>
            elementProducer("includeLocs", stringValue(s)))
        ).flatten)))
    )
  }

  /**
   * Takes the documents returned by the aggregation pipeline and writes them to a specified collection
   * http://docs.mongodb.org/manual/reference/operator/aggregation/out/#pipe._S_out
   * @param collection the name of the output collection
   */
  case class Out(collection: String) extends PipelineOperator {
    def makePipe =
      makeDocument(Seq(elementProducer("$out", stringValue(collection))))
  }

  case class SumField(field: String) extends GroupFunction {
    val makeFunction = makeDocument(Seq(elementProducer(
      "$sum", stringValue("$" + field)
    )))
  }

  case class SumValue(value: Int) extends GroupFunction {
    val makeFunction = makeDocument(Seq(elementProducer(
      "$sum", intValue(value)
    )))
  }

  case class Avg(field: String) extends GroupFunction {
    val makeFunction = makeDocument(Seq(elementProducer(
      "$avg", stringValue("$" + field)
    )))
  }

  case class First(field: String) extends GroupFunction {
    val makeFunction = makeDocument(Seq(elementProducer(
      "$first", stringValue("$" + field)
    )))
  }

  case class Last(field: String) extends GroupFunction {
    val makeFunction = makeDocument(Seq(elementProducer(
      "$last", stringValue("$" + field)
    )))
  }

  case class Max(field: String) extends GroupFunction {
    val makeFunction = makeDocument(Seq(elementProducer(
      "$max", stringValue("$" + field)
    )))
  }

  case class Min(field: String) extends GroupFunction {
    val makeFunction = makeDocument(Seq(elementProducer(
      "$min", stringValue("$" + field)
    )))
  }

  case class Push(field: String) extends GroupFunction {
    val makeFunction = makeDocument(Seq(elementProducer(
      "$push", stringValue("$" + field)
    )))
  }

  case class PushMulti(fields: (String, String)*) extends GroupFunction {
    val makeFunction = makeDocument(Seq(elementProducer(
      "$push",
      makeDocument(fields.map(field =>
        elementProducer(field._1, stringValue("$" + field._2))))
    )))
  }

  case class AddToSet(field: String) extends GroupFunction {
    val makeFunction = makeDocument(Seq(elementProducer(
      "$addToSet", stringValue("$" + field)
    )))
  }

  /** The [[https://docs.mongodb.com/manual/reference/operator/aggregation/stdDevPop/ \$stdDevPop]] group accumulator (since MongoDB 3.2) */
  case class StdDevPop(expression: pack.Value) extends GroupFunction {
    val makeFunction = makeDocument(Seq(elementProducer(
      "$stdDevPop", expression
    )))
  }

  /** The [[https://docs.mongodb.com/manual/reference/operator/aggregation/stdDevSamp/ \$stdDevSamp]] group accumulator (since MongoDB 3.2) */
  case class StdDevSamp(expression: pack.Value) extends GroupFunction {
    val makeFunction = makeDocument(Seq(elementProducer(
      "$stdDevSamp", expression
    )))
  }
}
