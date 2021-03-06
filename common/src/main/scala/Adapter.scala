
package zynq

import scala.math.min
import Chisel._
import uncore.tilelink._
import uncore.tilelink2.LazyModule
import coreplex.BaseCoreplexBundle
import junctions._
import cde.Parameters
import uncore.devices.{DebugBusIO, DebugBusReq, DebugBusResp, DMKey}
import uncore.devices.DbBusConsts._

class ZynqAdapter(implicit val p: Parameters)
    extends Module with HasNastiParameters {
  val io = new Bundle {
    val nasti = (new NastiIO).flip
    val reset = Bool(OUTPUT)
    val debug = new DebugBusIO
  }
  require(nastiXDataBits == 32)

  val aw = io.nasti.aw
  val ar = io.nasti.ar
  val w = io.nasti.w
  val r = io.nasti.r
  val b = io.nasti.b

  // Writing to 0x0, simply updates the contents of the register without
  // validating the request
  // Writing to 0x8, sets the valid register without changing the payload
  // Write-Only
  val REQ_PAYLOAD_ADDR = 0x0
  val REQ_VALID_ADDR = 0x8
  // Read-Only
  val RESP_ADDR = 0x10
  val RESET_ADDR = 0x20

  val debugAddrSize = p(DMKey).nDebugBusAddrSize
  val opOffset = io.debug.req.bits.data.getWidth % w.bits.data.getWidth
  val addrOffset = opOffset + io.debug.req.bits.op.getWidth


  val reqReg = RegInit({
    val init = Wire(Valid(new DebugBusReq(debugAddrSize)))
    init.valid := Bool(false)
    init
  })

  val respReg = RegInit({
    val init = Wire(Valid(new DebugBusResp))
    init.valid := Bool(false)
    init
  })

  val awReady = RegEnable(Bool(false), Bool(true), aw.fire())
  val wReady = RegEnable(Bool(false), Bool(true), w.fire() && w.bits.last)
  val arReady = RegInit(Bool(false))
  val rValid = RegInit(Bool(false))
  val bValid = RegInit(Bool(false))
  val bId = RegEnable(aw.bits.id, aw.fire())
  val rId = RegEnable(ar.bits.id, ar.fire())
  val wData = RegEnable(w.bits.data, w.fire() && w.bits.last)
  val wAddr = RegEnable(aw.bits.addr(5,0), aw.fire())
  val rAddr = RegEnable(ar.bits.addr(5,0), ar.fire())
  val resetReg = RegInit(Bool(false))

  val rData = Mux(rAddr(2), Cat(respReg.bits.resp, respReg.bits.data(33,32)),
                  respReg.bits.data(31,0))

  io.reset := Bool(false)

  when ((aw.fire() || ~aw.ready) && ((w.fire() && w.bits.last) || ~w.ready)){
    bValid := Bool(true)
  }

  when(b.fire()){
    when((wAddr) === UInt(REQ_VALID_ADDR)){
      reqReg.valid := Bool(true)
      respReg.valid := Bool(false)
    }.elsewhen((wAddr) === UInt(RESET_ADDR)){
      resetReg := Bool(true)
    }.otherwise{
      when(wAddr(2)){
        reqReg.bits.addr := wData(addrOffset+debugAddrSize-1, addrOffset)
        reqReg.bits.op := wData(addrOffset-1, opOffset)
        reqReg.bits.data := Cat(wData(opOffset-1,0), reqReg.bits.data(31,0))
      }.otherwise{
        reqReg.bits.data := Cat(reqReg.bits.data(33,32),wData)
      }
    }
    awReady := Bool(true)
    wReady := Bool(true)
    bValid := Bool(false)
  }

  when(ar.fire()){
    rValid := Bool(true)
  }

  when(r.fire() && r.bits.last){
    rValid := Bool(false)
  }

  when(io.debug.req.fire()){
    reqReg.valid := Bool(false)
  }

  when(io.debug.resp.fire()){
    respReg.valid := Bool(true)
    respReg.bits := io.debug.resp.bits
  }
  when(resetReg) {
    resetReg := Bool(false)
  }

  io.reset := resetReg

  ar.ready := respReg.valid
  aw.ready := awReady
  w.ready := wReady
  r.valid := rValid
  r.bits := NastiReadDataChannel(rId, rData)
  b.valid := bValid
  b.bits := NastiWriteResponseChannel(bId)

  io.debug.resp.ready := ~respReg.valid
  io.debug.req.valid := reqReg.valid
  io.debug.req.bits := reqReg.bits

  assert(!w.valid || w.bits.strb.andR,
    "Nasti to DebugBusIO converter cannot take partial writes")
  assert(!ar.valid ||
    ar.bits.len === UInt(0) ||
    ar.bits.burst === NastiConstants.BURST_FIXED,
    "Nasti to DebugBusIO converter can only take fixed bursts")
  assert(!aw.valid ||
    aw.bits.len === UInt(0) ||
    aw.bits.burst === NastiConstants.BURST_FIXED,
    "Nasti to DebugBusIO converter can only take fixed bursts")
}

object AdapterParams {
  def apply(p: Parameters) = p.alterPartial({
    case NastiKey => NastiParameters(
      dataBits = 32,
      addrBits = 32,
      idBits = 12)
  })
}

trait PeripheryZynq extends LazyModule {
  implicit val p: Parameters
}

trait PeripheryZynqBundle {
  implicit val p: Parameters

  val ps_axi_slave = new NastiIO()(AdapterParams(p)).flip
}

trait PeripheryZynqModule {
  implicit val p: Parameters
  val outer: PeripheryZynq
  val io: PeripheryZynqBundle
  val coreplexIO: BaseCoreplexBundle

  val adapter = Module(new ZynqAdapter()(AdapterParams(p)))
  adapter.io.nasti <> io.ps_axi_slave
  coreplexIO.debug <> adapter.io.debug
}
