import chisel3._
import chisel3.util._
import ZirconConfig.TLBParams

class AddressTranslationControl(p: TLBParams = TLBParams()) extends Bundle {
    val enabled = Bool()
    val asid = UInt(p.asidBits.W)
    val privilege = UInt(2.W)
    val mxr = Bool()
    val sum = Bool()
}

class TLBManagementIO(p: TLBParams = TLBParams()) extends Bundle {
    val control = Input(new AddressTranslationControl(p))
    val refill = Flipped(Valid(new TLBRefill(p)))
    val flush = Input(Bool())
}

object MMUPermission {
    def userAllowed(userPage: Bool, privilege: UInt, sum: Bool, execute: Boolean): Bool = {
        val user = privilege === 0.U
        val supervisor = privilege === 1.U
        val supervisorUserPage = if (execute) false.B else sum
        (user && userPage) || (supervisor && (!userPage || supervisorUserPage))
    }

    def instruction(entry: TLBLookupResponse, control: AddressTranslationControl): Bool =
        entry.permissions.execute && entry.permissions.accessed &&
            userAllowed(entry.permissions.user, control.privilege, control.sum, execute = true)

    def data(entry: TLBLookupResponse, control: AddressTranslationControl, store: Bool): Bool = {
        val readable = entry.permissions.read || (control.mxr && entry.permissions.execute)
        val access = Mux(store, entry.permissions.write && entry.permissions.dirty, readable)
        access && entry.permissions.accessed &&
            userAllowed(entry.permissions.user, control.privilege, control.sum, execute = false)
    }
}
