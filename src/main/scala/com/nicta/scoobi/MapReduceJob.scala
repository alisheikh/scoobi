/**
  * Copyright: [2011] Ben Lever
  */
package com.nicta.scoobi

import java.io.File
import org.apache.hadoop.io.NullWritable
import org.apache.hadoop.fs.FileSystem
import org.apache.hadoop.fs.Path
import org.apache.hadoop.mapred.JobConf
import org.apache.hadoop.mapred.JobClient
import org.apache.hadoop.mapred.FileOutputFormat
import org.apache.hadoop.mapred.SequenceFileOutputFormat
import org.apache.hadoop.mapred.Partitioner
import org.apache.hadoop.mapred.lib.MultipleOutputs
import org.apache.hadoop.io.SequenceFile
import org.apache.hadoop.io.compress.GzipCodec
import scala.collection.mutable.{Map => MMap}


/** A class that defines a single Hadoop MapRedcue job. */
class MapReduceJob {

  import scala.collection.mutable.{Set => MSet, Map => MMap}

  /* Keep track of all the mappers for each input channel. */
  private val mappers: MMap[DataSource, MSet[TaggedMapper[_,_,_]]] = MMap.empty
  private val combiners: MSet[TaggedCombiner[_]] = MSet.empty
  private val reducers: MMap[List[_ <: DataSink], TaggedReducer[_,_,_]] = MMap.empty

  /* The types that will be combined together to form (K2, V2). */
  private val keyTypes: MMap[Int, (Manifest[_], HadoopWritable[_], Ordering[_])] = MMap.empty
  private val valueTypes: MMap[Int, (Manifest[_], HadoopWritable[_])] = MMap.empty


  /** Add an input mapping function to thie MapReduce job. */
  def addTaggedMapper[A, K, V](input: DataSource, m: TaggedMapper[A, K, V]): Unit = {
    if (!mappers.contains(input))
      mappers += (input -> MSet(m))
    else
      mappers(input) += m

    m.tags.foreach { tag =>
      keyTypes   += (tag -> (m.mK, m.wtK, m.ordK))
      valueTypes += (tag -> (m.mV, m.wtV))
    }
  }

  /** Add a combiner function to this MapReduce job. */
  def addTaggedCombiner[V](c: TaggedCombiner[V]): Unit = {
    combiners += c
  }

  /** Add an output reducing function to this MapReduce job. */
  def addTaggedReducer[K, V, B](outputs: Set[_ <: DataSink], r: TaggedReducer[K, V, B]): Unit = {
    reducers += (outputs.toList -> r)
  }

  /** Take this MapReduce job and run it on Hadoop. */
  def run() = {

    val jobConf = new JobConf(Scoobi.conf)

    /* Job output always goes to temporary dir from which files are subsequently moved from
     * once the job is finished. */
    val tmpOutputPath = new Path(Scoobi.getWorkingDirectory(jobConf), "tmp-out")

    /** Make temporary JAR file for this job. At a minimum need the Scala runtime
      * JAR, the Scoobi JAR, and the user's application code JAR(s). */
    val tmpFile = File.createTempFile("scoobi-job-", ".jar")
    var jar = new JarBuilder(tmpFile.getAbsolutePath)
    jobConf.setJar(jar.name)

    jar.addContainingJar(classOf[List[_]])        //  Scala
    jar.addContainingJar(this.getClass)           //  Scoobi
    Scoobi.getUserJars.foreach { jar.addJar(_) }  //  User JARs


    /** (K2,V2):
      *   - are (TaggedKey, TaggedValue), the wrappers for all K-V types
      *   - generate their runtime classes and add to JAR */
    val id = UniqueId.get
    val tkRtClass = TaggedKey("TK" + id, keyTypes.toMap)
    val tvRtClass = TaggedValue("TV" + id, valueTypes.toMap)
    val tpRtClass = TaggedPartitioner("TP" + id, keyTypes.size)


    jar.addRuntimeClass(tkRtClass)
    jar.addRuntimeClass(tvRtClass)
    jar.addRuntimeClass(tpRtClass)

    jobConf.setMapOutputKeyClass(tkRtClass.clazz)
    jobConf.setMapOutputValueClass(tvRtClass.clazz)
    jobConf.setPartitionerClass(tpRtClass.clazz.asInstanceOf[Class[_ <: Partitioner[_,_]]])

    jobConf.setCompressMapOutput(true)
    jobConf.setMapOutputCompressorClass(classOf[GzipCodec])


    /** Mappers:
      *     - generate runtime class (ScoobiWritable) for each input value type and add to JAR (any
      *       mapper for a given input channel can be used as they all have the same input type
      *     - use ChannelInputs to specify multiple mappers through jobconf */
    val inputChannels = mappers.toList.zipWithIndex
    inputChannels.foreach { case ((input, ms), ix) =>
      val mapper = ms.head
      val valRtClass = ScoobiWritable(input.inputTypeName, mapper.mA, mapper.wtA)
      jar.addRuntimeClass(valRtClass)
      ChannelInputFormat.addInputChannel(jobConf, ix, input.inputPath, input.inputFormat)
    }

    DistributedObject.pushObject(jobConf, inputChannels map { case((_, ms), ix) => (ix, ms.toSet) } toMap, "scoobi.input.mappers")
    jobConf.setMapperClass(classOf[MscrMapper[_,_,_]])


    /** Combiners:
      *   - only need to make use of Hadoop's combiner facility if actual combiner
      *   functions have been added
      *   - use distributed cache to push all combine code out */
    if (!combiners.isEmpty) {
      val combinerMap: Map[Int, TaggedCombiner[_]] = combiners.map(tc => (tc.tag, tc)).toMap
      DistributedObject.pushObject(jobConf, combinerMap, "scoobi.combiners")
      jobConf.setCombinerClass(classOf[MscrCombiner[_]])
    }


    /** Reducers:
      *     - add a named output for each output channel
      *     - generate runtime class (ScoobiWritable) for each output value type and add to JAR */
    FileOutputFormat.setOutputPath(jobConf, tmpOutputPath)
    reducers.foreach { case (outputs, reducer) =>
      val valRtClass = ScoobiWritable(outputs.head.outputTypeName, reducer.mB, reducer.wtB)
      jar.addRuntimeClass(valRtClass)
      outputs.zipWithIndex.foreach { case (output, ix) =>
        MultipleOutputs.addNamedOutput(jobConf, "ch" + reducer.tag + "out" + ix, output.outputFormat,
                                       classOf[NullWritable], valRtClass.clazz)
      }
    }

    DistributedObject.pushObject(jobConf, reducers map { case (os, tr) => (tr.tag, (os.size, tr)) } toMap, "scoobi.output.reducers")
    jobConf.setReducerClass(classOf[MscrReducer[_,_,_]])


    /* Compress all sequence file output. (TODO - does it apply to all output?) */
//  FileOutputFormat.setCompressOutput(jobConf, true)
//  FileOutputFormat.setOutputCompressorClass(jobConf, classOf[GzipCodec])
//  SequenceFileOutputFormat.setOutputCompressionType(jobConf, SequenceFile.CompressionType.BLOCK)


    /* Run job then tidy-up. */
    jar.close()
    JobClient.runJob(jobConf)
    tmpFile.delete


    /* Move named outputs to the correct directories */
    val fs = FileSystem.get(jobConf)
    val outputFiles = fs.listStatus(tmpOutputPath) map { _.getPath }
    val FileName = """ch(\d+)out(\d+)-.-\d+""".r

    reducers.foreach { case (outputs, reducer) =>

      outputs.zipWithIndex.foreach { case (output, ix) =>
        outputFiles filter (forOutput) foreach { srcPath =>
          fs.mkdirs(output.outputPath)
          fs.rename(srcPath, new Path(output.outputPath, srcPath.getName))
        }

        def forOutput = (f: Path) => f.getName match {
          case FileName(t, i) => t.toInt == reducer.tag && i.toInt == ix
          case _              => false
        }

      }
    }

    fs.delete(tmpOutputPath, true)
  }
}


object MapReduceJob {

  /** Construct a MapReduce job from an MSCR. */
  def apply(mscr: MSCR): MapReduceJob = {
    val job = new MapReduceJob
    val mapperTags: MMap[AST.Node[_], Set[Int]] = MMap.empty

    /* Tag each output channel with a unique index. */
    mscr.outputChannels.zipWithIndex.foreach { case (oc, tag) =>

      def addTag(n: AST.Node[_], tag: Int): Unit = {
        val s = mapperTags.getOrElse(n, Set())
        mapperTags += (n -> (s + tag))
      }

      /* Build up a map of mappers to output channel tags. */
      oc match {
        case GbkOutputChannel(_, Some(AST.Flatten(ins)), _, _)  => ins.foreach { in => addTag(in, tag) }
        case GbkOutputChannel(_, None, AST.GroupByKey(in), _)   => addTag(in, tag)
        case BypassOutputChannel(_, origin)                     => addTag(origin, tag)
      }

      /* Add combiner functionality from output channel descriptions. */
      oc match {
        case GbkOutputChannel(_, _, _, JustCombiner(c))       => job.addTaggedCombiner(c.mkTaggedCombiner(tag))
        case GbkOutputChannel(_, _, _, CombinerReducer(c, _)) => job.addTaggedCombiner(c.mkTaggedCombiner(tag))
        case _                                                => Unit
      }

      /* Add reducer functionality from output channel descriptions. */
      oc match {
        case GbkOutputChannel(outputs, _, _, JustCombiner(c))       => job.addTaggedReducer(outputs, c.mkTaggedReducer(tag))
        case GbkOutputChannel(outputs, _, _, JustReducer(r))        => job.addTaggedReducer(outputs, r.mkTaggedReducer(tag))
        case GbkOutputChannel(outputs, _, _, CombinerReducer(_, r)) => job.addTaggedReducer(outputs, r.mkTaggedReducer(tag))
        case GbkOutputChannel(outputs, _, g, Empty())               => job.addTaggedReducer(outputs, g.mkTaggedReducer(tag))
        case BypassOutputChannel(outputs, origin)                   => job.addTaggedReducer(outputs, origin.mkTaggedIdentityReducer(tag))
      }
    }

    /* Add mapping functionality from input channel descriptions. */
    mscr.inputChannels.foreach { ic =>
      ic match {
        case b@BypassInputChannel(input, origin) => {
          job.addTaggedMapper(input, origin.mkTaggedIdentityMapper(mapperTags(origin)))
        }
        case MapperInputChannel(input, mappers) => mappers.foreach { m =>
          job.addTaggedMapper(input, m.mkTaggedMapper(mapperTags(m)))
        }
      }
    }

    job
  }
}

object UniqueId extends UniqueInt
