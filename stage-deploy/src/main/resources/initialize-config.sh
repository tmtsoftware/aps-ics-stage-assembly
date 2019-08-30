if [ $# -eq 0 ]; then
  echo "Usage: initialize-config.sh <config service IP> "
  exit 0
fi


curl -X POST --data-binary @FiberSourceStageAssembly.conf  http://$1:5000/config/org/tmt/aps/ics/FiberSourceStageAssembly.conf

curl -X POST --data-binary @PupilMaskStageAssembly.conf  http://$1:5000/config/org/tmt/aps/ics/PupilMaskStageAssembly.conf

curl -X POST --data-binary @DmOpticStageAssembly.conf  http://$1:5000/config/org/tmt/aps/ics/DmOpticStageAssembly.conf

curl -X POST --data-binary @StageContainer.conf  http://$1:5000/config/org/tmt/aps/ics/StageContainer.conf
