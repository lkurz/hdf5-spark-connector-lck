package org.hdfgroup.spark.hdf5

import java.io.File

import org.apache.spark.sql.Row
import org.hdfgroup.spark.hdf5.ScanExecutor._
import org.hdfgroup.spark.hdf5.reader.HDF5Schema.ArrayVar
import org.hdfgroup.spark.hdf5.reader.{DatasetReader, HDF5Reader}
import org.slf4j.LoggerFactory

import scala.language.existentials

object ScanExecutor {
  sealed trait ScanItem {
    val dataset: ArrayVar[_]
    val ioSize: Int
  }
  case class UnboundedScan(dataset: ArrayVar[_], ioSize: Int, cols: Array[String]) extends ScanItem
  case class BoundedScan(dataset: ArrayVar[_], ioSize: Int, blockNumber: Long = 0, cols: Array[String]) extends ScanItem
  case class BoundedMDScan(dataset: ArrayVar[_], ioSize: Int, blockDimensions: Array[Int], offset: Array[Long], cols: Array[String]) extends ScanItem
}

class ScanExecutor(filePath: String, fileID: Integer) extends Serializable {

  private val log = LoggerFactory.getLogger(getClass)

  private val dataSchema = Array[String]("FileID", "Index", "Value")

  // Returns a sequence of the virtual table rows or the data/index/fileID rows.
  // The data rows are hard-coded to efficiently read the data.
  // PrunedScans with two columns must be checked to return the columns in the correct order.
  def execQuery[T](scanItem: ScanItem): Seq[Row] = {
    log.trace("{}", Array[AnyRef](scanItem))

    scanItem match {
      case UnboundedScan(dataset, _, cols) => dataset.path match {
        case "sparky://files" => {
          if (cols.length == 0)
            Seq(Row(dataset.fileID, dataset.fileName, dataset.realSize))
          else {
            var listRows = List[Any]()
            for (col <- cols) {
              if (col == "FileID")
                listRows = listRows :+ dataset.fileID
              else if (col == "FilePath")
                listRows = listRows :+ dataset.fileName
              else if (col == "FileSize")
                listRows = listRows :+ dataset.realSize
            }
            Seq(Row.fromSeq(listRows))
          }
        }

        case "sparky://datasets" => {
          val typeInfo = dataset.contains.toString
          if (cols.length == 0)
            Seq(Row(dataset.fileID, dataset.realPath,
              typeInfo.substring(0, typeInfo.indexOf('(')),
              dataset.dimension, dataset.realSize))
          else {
            var listRows = List[Any]()
            for (col <- cols) {
              if (col == "FileID")
                listRows = listRows :+ dataset.fileID
              else if (col == "DatasetPath")
                listRows = listRows :+ dataset.realPath
              else if (col == "ElementType")
                listRows = listRows :+ typeInfo.substring(0, typeInfo.indexOf('('))
              else if (col == "Dimensions")
                listRows = listRows :+ dataset.dimension
              else if (col == "ElementCount")
                listRows = listRows :+ dataset.realSize
            }
            Seq(Row.fromSeq(listRows))
          }
        }

        case "sparky://attributes" => {
          val typeInfo = dataset.contains.toString
          if (cols.length == 0)
            Seq(Row(dataset.fileID, dataset.realPath, dataset.attribute,
              typeInfo.substring(0, typeInfo.indexOf('(')),
              dataset.dimension))
          else {
            var listRows = List[Any]()
            val typeInfo = dataset.contains.toString
            for (col <- cols) {
              if (col == "FileID")
                listRows = listRows :+ dataset.fileID
              else if (col == "ObjectPath")
                listRows = listRows :+ dataset.realPath
              else if (col == "AttributeName")
                listRows = listRows :+ dataset.attribute
              else if (col == "ElementType")
                listRows = listRows :+ typeInfo.substring(0, typeInfo.indexOf('('))
              else if (col == "Dimensions")
                listRows = listRows :+ dataset.dimension
            }
            Seq(Row.fromSeq(listRows))
          }
        }

        case _ => {
          val col =
            if (cols.length == 0) dataSchema
            else cols
          val hasValue = col contains "Value"
          val hasIndex = col contains "Index"
          val hasID = col contains "FileID"
          if (hasValue) {
            val dataReader = newDatasetReader(dataset)(_.readDataset())
            if (hasIndex) {
              val indexed = dataReader.zipWithIndex
              if (hasID) indexed.map { case (x, index) => Row(fileID, index.toLong, x) }
              else indexed.map { case (x, index) => {
                if (col(0) == "Index") Row(index.toLong, x)
                else Row(x, index.toLong)
              }}
            } else {
              if (hasID) dataReader.map { x => {
                if (col(0) == "FileID") Row(fileID, x)
                else Row(x, fileID)
              }}
              else dataReader.map { x => Row(x) }
            }
          } else {
            if (hasIndex) {
              val indexed = (0L until dataset.size)
              if (hasID) indexed.map { x => {
                if (col(0) == "FileID") Row(fileID, x)
                else Row(x, fileID)
              }}
              else indexed.map { x => Row(x) }
            } else Seq(Row(fileID))
          }
        }
      }

      case BoundedScan(dataset, ioSize, offset, cols) => {
        val col =
          if (cols.length == 0) dataSchema
          else cols
        val hasValue = col contains "Value"
        val hasIndex = col contains "Index"
        val hasID = col contains "FileID"
        if (hasValue) {
          val dataReader = newDatasetReader(dataset)(_.readDataset(ioSize, offset))
          if (hasIndex) {
            val indexed = dataReader.zipWithIndex
            if (hasID) indexed.map { case (x, index) => Row(fileID, offset + index.toLong, x) }
            else indexed.map { case (x, index) => {
              if (col(0) == "Index") Row(offset + index.toLong, x)
              else Row(x, offset + index.toLong)
            }}
          } else {
            if (hasID) dataReader.map { x => {
              if (col(0) == "FileID") Row(fileID, x)
              else Row(x, fileID)
            }}
            else dataReader.map { x => Row(x) }
          }
        } else {
          if (hasIndex) {
            val indexed = (0L until dataset.size)
            if (hasID) indexed.map { x => {
              if (col(0) == "FileID") Row(fileID, offset + x.toLong)
              else Row(offset + x.toLong, fileID)
            }}
            else indexed.map { x => Row(offset + x.toLong) }
          } else Seq(Row(fileID))
        }
      }

      case BoundedMDScan(dataset, ioSize, blockDimensions, offset, cols) => {
        val col =
          if (cols.length == 0) dataSchema
          else cols
        val hasValue = col contains "Value"
        val hasIndex = col contains "Index"
        val hasID = col contains "FileID"
        val d = dataset.dimension
        val edgeBlock = (offset, blockDimensions, d).zipped.map { case (offset, dim, d) => {
          if ((offset / dim) < ((Math.floor(d / dim)).toInt)) dim
          else d % offset
        }}
        val blockFill = offset(0) * d(1)
        if (hasValue) {
          // Calculations to correctly map the index of each datapoint in
          // respect to the overall linearized matrix.
          val dataReader = newDatasetReader(dataset)(_.readDataset(blockDimensions, offset))
          if (hasIndex) {
            val indexed = dataReader.zipWithIndex
            if (hasID) indexed.map { case (x, index) => Row(fileID, blockFill + (index - index % edgeBlock(1))
              / edgeBlock(0) * d(1) + index % edgeBlock(1) + offset(1), x)
            }
            else {
              indexed.map { case (x, index) => {
                val globalIndex = blockFill + (index - index % edgeBlock(1)) / edgeBlock(1) * d(1) + index % edgeBlock(1) + offset(1)
                if (col(0) == "Index") Row(globalIndex, x)
                else Row(x, globalIndex)
              }}
            }
          } else {
            if (hasID) dataReader.map { x => {
              if (col(0) == "FileID") Row(fileID, x)
              else Row(x, fileID)
            }
            }
            else dataReader.map { x => Row(x) }
          }
        } else {
          if (hasIndex) {
            val indexed = (0L until edgeBlock(0) * edgeBlock(1).toLong)
            if (hasID) indexed.map { x => {
              val globalIndex = blockFill + (x - x % edgeBlock(1)) / edgeBlock(1) * d(1) + x % edgeBlock(1) + offset(1)
              if (col(0) == "FileID") Row(fileID, globalIndex)
              else Row(globalIndex, fileID)
            }}
            else {
              indexed.map { x => Row(blockFill + (x - x % edgeBlock(1)) / edgeBlock(1) * d(1) + x % edgeBlock(1) + offset(1)) }
            }
          } else Seq(Row(fileID))
        }
      }
    }
  }

  def openReader[T](fun: HDF5Reader => T): T = {
    log.trace("{}", Array[AnyRef](fun))

    val file = new File(filePath)
    val reader = new HDF5Reader(file, fileID)
    val result = fun(reader)
    reader.close()
    result
  }

  def newDatasetReader[S, T](node: ArrayVar[T])(fun: DatasetReader[T] => S): S = {
    log.trace("{} {}", Array[AnyRef](node, fun))

    openReader(reader => reader.getDataset(node)(fun))
  }
}
