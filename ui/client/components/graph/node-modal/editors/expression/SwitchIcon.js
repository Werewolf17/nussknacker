import PropTypes from "prop-types"
import React from "react"
import * as LoaderUtils from "../../../../../common/LoaderUtils"
import classes from "../../../../../stylesheets/graph.styl"
import cn from "classnames"

export default function SwitchIcon(props) {

  const {switchable, readOnly, hint, onClick, displayRawEditor, shouldShowSwitch} = props

  const title = () => readOnly ? "Switching to basic mode is disabled. You are in read-only mode" : hint

  return (
    shouldShowSwitch ? (
      <button
        id={"switch-button"}
        className={cn("inlined", "switch-icon", displayRawEditor && classes.switchIconActive, readOnly && classes.switchIconReadOnly)}
        onClick={onClick}
        disabled={!switchable || readOnly}
        title={title()}
      >
        {/* Keep in mind that we base on structure of given svg in related styles */}
        <div dangerouslySetInnerHTML={{__html: LoaderUtils.loadSvgContent("buttons/switch.svg")}}/>
      </button>
    ) : null
  )
}

SwitchIcon.propTypes = {
  switchable: PropTypes.bool,
  hint: PropTypes.string,
  onClick: PropTypes.func,
  shouldShowSwitch: PropTypes.bool,
  displayRawEditor: PropTypes.bool,
  readOnly: PropTypes.bool,
}