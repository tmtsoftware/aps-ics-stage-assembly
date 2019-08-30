package org.tmt.aps.ics.stageassembly

import akka.util.Timeout
import csw.command.api.StateMatcher
import csw.params.core.generics.Parameter
import csw.params.core.models.{Prefix, Struct}
import csw.params.core.states.{CurrentState, StateName}

/**
 * The DemandMatcher checks the CurrentStatus for equality with the items in the DemandState.
 * This version tests for equality so it may not work the best with floating point values.
 * Note: If the withUnits flag is set, the equality check with also compare units. False is the default
 * so normally units are ignored for this purpose.
 *
 * @param demand a DemandState that will provide the items for determining completion with the CurrentState
 * @param withUnits when True, units are compared. When false, units are not compared. Default is false.
 * @param timeout a timeout for which the matching should be executed. Once the timeout occurs, complete the match with
 *                MatchFailed response and appropriate failure exception.
 */
case class GalilPositionMatcher(axis: Char,
                                position: Int,
                                prefixObj: Prefix,
                                stateNameObj: StateName,
                                withUnits: Boolean = false,
                                timeout: Timeout)
    extends StateMatcher {

  /**
   * The prefix of the destination component for which the current state is being matched
   *
   * @return the prefix of destination component
   */
  def prefix = prefixObj

  /**
   * The name of the state to match for
   *
   * @return the name of the state
   */
  def stateName = stateNameObj

  /**
   * A predicate to match the current state
   *
   * @param current current state to be matched as represented by [[csw.messages.params.states.CurrentState]]
   * @return true if match is successful, false otherwise
   */
  def check(current: CurrentState): Boolean = {

    try {

      val axisCsParam = current.paramSet.find(x => x.keyName == axis.toString()).get

      val struct: Struct = extractStructParam(axisCsParam).value(0)

      val motorPositionParam = struct.paramSet.find((x: Parameter[_]) => x.keyName == "motorPosition")

      position == motorPositionParam.get.value(0)

    } catch {
      case e: Throwable => false
    }

  }

  def extractStructParam(input: Parameter[_]): Parameter[Struct] = {
    input match {
      case x: Parameter[Struct] => x
      case _                    => throw new Exception("unexpected exception")
    }
  }

}
