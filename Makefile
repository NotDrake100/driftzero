.PHONY: test lint validate

test:
	PYTHONPATH=ml/src python -m unittest discover -s ml/tests -v

lint:
	python -m ruff check ml

validate:
	python -m json.tool contracts/sensor_frame.schema.json >/dev/null
	python -m json.tool contracts/navigation_state.schema.json >/dev/null
	PYTHONPATH=ml/src python -m unittest discover -s ml/tests -v
