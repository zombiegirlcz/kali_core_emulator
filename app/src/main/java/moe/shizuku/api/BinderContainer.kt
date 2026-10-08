package moe.shizuku.api

import android.os.IBinder
import android.os.Parcel
import android.os.Parcelable

/**
 * MUSÍ zůstat přesně v tomto package/jménu třídy — klientská knihovna
 * `rikka.shizuku:api` čte `IBinder` z Bundle a deserializuje ho jako
 * `moe.shizuku.api.BinderContainer` (Parcelable čtení dělá Class.forName
 * na jméno uložené v Parcelu).
 */
class BinderContainer(@JvmField val binder: IBinder?) : Parcelable {

    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeStrongBinder(binder)
    }

    companion object CREATOR : Parcelable.Creator<BinderContainer> {
        override fun createFromParcel(source: Parcel): BinderContainer =
            BinderContainer(source.readStrongBinder())

        override fun newArray(size: Int): Array<BinderContainer?> = arrayOfNulls(size)
    }
}
