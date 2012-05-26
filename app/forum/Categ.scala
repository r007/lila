package lila
package forum

import com.novus.salat.annotations.Key

case class Categ(
    @Key("_id") slug: String,
    name: String,
    desc: String) {
}