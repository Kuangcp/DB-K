.PHONY: run install packageAppImage

run:
	gradle run

packageAppImage:
	gradle makeAppImage

install:
	gradle createDistributable