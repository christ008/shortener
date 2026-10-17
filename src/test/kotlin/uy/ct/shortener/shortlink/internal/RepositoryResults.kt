package uy.ct.shortener.shortlink.internal

import uy.ct.shortener.shortlink.Actor
import uy.ct.shortener.shortlink.InsertResult
import uy.ct.shortener.shortlink.LinkLookup
import uy.ct.shortener.shortlink.LinkStatus
import uy.ct.shortener.shortlink.ShortLink
import kotlin.test.assertIs

/** Test shortcuts that unwrap a repository answer, failing the test when it is not the expected one. */
fun InsertResult.created(): ShortLink = assertIs<InsertResult.Created>(this).link

fun LinkLookup.found(): ShortLink = assertIs<LinkLookup.Found>(this).link

val ShortLink.disabledBy: Actor get() = assertIs<LinkStatus.Disabled>(status).by
