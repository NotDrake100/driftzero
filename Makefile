.PHONY: test lint validate jvm android

PYTHON ?= python3

test:
	PYTHONPATH=ml/src $(PYTHON) -m unittest discover -s ml/tests -v

lint:
	$(PYTHON) -m ruff check ml

validate:
	$(PYTHON) -m json.tool contracts/sensor_frame.schema.json >/dev/null
	$(PYTHON) -m json.tool contracts/navigation_state.schema.json >/dev/null
	$(PYTHON) -m json.tool contracts/examples/sensor_frame.accelerometer.json >/dev/null
	$(PYTHON) -m json.tool contracts/examples/navigation_state.sample.json >/dev/null
	PYTHONPATH=ml/src $(PYTHON) -m unittest discover -s ml/tests -v

jvm:
	./gradlew :navigation-core:test --no-daemon

android:
	./gradlew :android-app:testDebugUnitTest :android-app:lintDebug :android-app:assembleDebug --no-daemon
